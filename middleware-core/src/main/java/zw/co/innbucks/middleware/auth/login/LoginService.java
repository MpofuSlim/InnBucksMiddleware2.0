package zw.co.innbucks.middleware.auth.login;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.exception.AccountLockedException;
import zw.co.innbucks.middleware.auth.exception.BackoffActiveException;
import zw.co.innbucks.middleware.auth.exception.InvalidCredentialsException;
import zw.co.innbucks.middleware.auth.exception.PinNotSetException;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.auth.pin.PinFormat;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.auth.refresh.RefreshToken;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.CustomerStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=65536,t=3,p=1$0123456789abcdef$0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd";

    private final CustomerRepository customerRepository;
    private final MsisdnNormalizerRegistry msisdnRegistry;
    private final PinHasher pinHasher;
    private final RefreshTokenService refreshTokenService;
    private final JwtIssuer jwtIssuer;
    private final AuthProperties authProperties;
    private final CountryProperties countryProperties;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional
    public LoginResult login(String rawMsisdn, String pin, String deviceHash) {
        if (!PinFormat.isValid(pin)) {
            throw new InvalidCredentialsException();
        }

        String normalisedMsisdn = msisdnRegistry.forDeployment().normalize(rawMsisdn);
        Optional<Customer> maybe = customerRepository.findByCountryAndMsisdn(
                countryProperties.country().name(), normalisedMsisdn);
        if (maybe.isEmpty()) {
            // Constant-time-ish: hash a dummy so unknown-user and wrong-pin take similar time.
            pinHasher.matches(pin, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        Customer customer = maybe.get();
        Instant now = clock.instant();
        AuthProperties.BruteForce bf = authProperties.bruteForce();

        // Customer registered but never completed PIN setup -> route mobile app
        // to the OTP+PIN-setup flow instead of pretending the credentials were wrong.
        if (customer.statusEnum() == CustomerStatus.PENDING_VERIFICATION
                || customer.getPinHash() == null) {
            auditService.record(AuditAction.PIN_NOT_SET, AuditOutcome.FAILURE,
                    customer.getId(), deviceHash);
            throw new PinNotSetException();
        }

        if (customer.statusEnum() == CustomerStatus.LOCKED
                && customer.getLockedUntil() != null
                && customer.getLockedUntil().isAfter(now)) {
            auditService.record(AuditAction.LOGIN_BLOCKED_LOCKED, AuditOutcome.FAILURE,
                    customer.getId(), deviceHash);
            throw new AccountLockedException(customer.getLockedUntil());
        }

        long backoffSeconds = computeBackoffSeconds(customer, now, bf);
        if (backoffSeconds > 0) {
            auditService.record(AuditAction.LOGIN_BLOCKED_BACKOFF, AuditOutcome.FAILURE,
                    customer.getId(), deviceHash);
            throw new BackoffActiveException(backoffSeconds);
        }

        if (!pinHasher.matches(pin, customer.getPinHash())) {
            customer.setFailedPinAttempts(customer.getFailedPinAttempts() + 1);
            customer.setLastFailedPinAt(now);
            customer.setUpdatedAt(now);
            if (customer.getFailedPinAttempts() >= bf.maxFailedAttemptsBeforeLock()) {
                customer.setStatus(CustomerStatus.LOCKED.dbValue());
                customer.setLockedUntil(now.plus(bf.lockDuration()));
                customerRepository.save(customer);
                auditService.record(AuditAction.ACCOUNT_LOCKED, AuditOutcome.FAILURE,
                        customer.getId(), deviceHash);
            } else {
                customerRepository.save(customer);
                auditService.record(AuditAction.LOGIN_FAILURE, AuditOutcome.FAILURE,
                        customer.getId(), deviceHash);
            }
            throw new InvalidCredentialsException();
        }

        customer.setFailedPinAttempts(0);
        customer.setLastFailedPinAt(null);
        customer.setLockedUntil(null);
        customer.setUpdatedAt(now);
        customerRepository.save(customer);

        RefreshToken refresh = refreshTokenService.issueNewFamily(customer.getId(), deviceHash);
        String accessToken = jwtIssuer.issue(new JwtIssuer.IssueRequest(
                customer.getId().toString(),
                customer.countryEnum(),
                customer.getKycTier(),
                CustomerScopes.DEFAULT,
                deviceHash,
                customer.getNationalIdHash()
        ));

        auditService.record(AuditAction.LOGIN_SUCCESS, AuditOutcome.SUCCESS,
                customer.getId(), deviceHash);

        return new LoginResult(accessToken, refresh.getSecret(), authProperties.accessTokenTtl());
    }

    private long computeBackoffSeconds(Customer customer, Instant now, AuthProperties.BruteForce bf) {
        if (customer.getFailedPinAttempts() <= 0 || customer.getLastFailedPinAt() == null) {
            return 0L;
        }
        long base = bf.backoffBase().toSeconds();
        long shift = Math.min(customer.getFailedPinAttempts() - 1, 6);
        long penaltySeconds = Math.min(base << shift, 60L);
        long elapsedSeconds = now.getEpochSecond() - customer.getLastFailedPinAt().getEpochSecond();
        long remaining = penaltySeconds - elapsedSeconds;
        return Math.max(remaining, 0L);
    }
}
