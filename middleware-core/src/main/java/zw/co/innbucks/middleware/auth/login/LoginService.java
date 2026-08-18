package zw.co.innbucks.middleware.auth.login;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.anomaly.AuthAnomalyDetector;
import zw.co.innbucks.middleware.anomaly.AuthFailureKind;
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
import zw.co.innbucks.middleware.customer.CustomerLockoutStore;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.CustomerStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=65536,t=3,p=1$0123456789abcdef$0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd";

    private final CustomerRepository customerRepository;
    private final CustomerLockoutStore lockoutStore;
    private final MsisdnNormalizerRegistry msisdnRegistry;
    private final PinHasher pinHasher;
    private final RefreshTokenService refreshTokenService;
    private final JwtIssuer jwtIssuer;
    private final AuthProperties authProperties;
    private final CountryProperties countryProperties;
    private final AuditService auditService;
    private final AuthAnomalyDetector anomalyDetector;
    private final Clock clock;

    /**
     * <b>This method deliberately owns NO transaction, and must never be given
     * one again.</b>
     *
     * <p>A Spring transaction acquires its pooled connection EAGERLY at begin
     * ({@code DataSourceTransactionManager.doBegin}; there is no
     * {@code LazyConnectionDataSourceProxy} in this repo), so an annotation
     * here holds a connection across the ~100-300ms Argon2id compare below —
     * including the dummy-hash burn on the unknown-MSISDN branch, which is the
     * enumeration half of a credential spray and therefore the highest-volume
     * path of all. Under a login burst the pool is drained by threads doing
     * arithmetic rather than I/O, and the audit write's nested REQUIRES_NEW
     * unit made it two connections per in-flight login.
     *
     * <p>Every customer write is therefore ONE self-atomic statement in
     * {@link CustomerLockoutStore}. That is also what removes a real lost
     * update: the failure counter used to be read-modify-write, so K racing
     * wrong PINs advanced it by 1 and the account never reached the cap. The
     * increment is now computed server-side, so K failures count K.
     *
     * <p>The failure increment survives the thrown
     * {@link InvalidCredentialsException} STRUCTURALLY — it commits on an
     * autocommit connection before the exception is even constructed. That is
     * what the old {@code noRollbackFor} bought declaratively, and it is
     * strictly stronger: the annotation named one exception type and would
     * have silently stopped working the moment a different throw was added to
     * that branch. Do NOT "restore" it — a bare {@code @Transactional} here
     * reintroduces both bugs at once.
     *
     * <p>No code after the atomic statement may read the lockout fields off
     * the in-memory {@code customer}; it is stale from that point on.
     */
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
            // Counted even though no account exists — attempts against numbers
            // that are NOT registered are the enumeration half of a spray, and
            // omitting them would hide the noisiest phase of the attack.
            anomalyDetector.recordFailure(AuthFailureKind.LOGIN, normalisedMsisdn);
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
            // Confirms the number IS registered, so it is an enumeration oracle
            // and belongs in the count. A genuine new customer hits this for one
            // account only; a sprayer hits it across many.
            anomalyDetector.recordFailure(AuthFailureKind.LOGIN, normalisedMsisdn);
            throw new PinNotSetException();
        }

        // This gate stays AHEAD of the hash. Moving it after the compare would
        // make every attempt on a locked account cost 64 MiB + one Argon2id,
        // turning the lockout into a CPU-exhaustion amplifier for an attacker
        // who has locked many accounts — and it would buy no timing privacy,
        // since the 423 body returns lockedUntil by design anyway.
        if (customer.statusEnum() == CustomerStatus.LOCKED
                && customer.getLockedUntil() != null
                && customer.getLockedUntil().isAfter(now)) {
            auditService.record(AuditAction.LOGIN_BLOCKED_LOCKED, AuditOutcome.FAILURE,
                    customer.getId(), deviceHash);
            anomalyDetector.recordFailure(AuthFailureKind.LOGIN, normalisedMsisdn);
            throw new AccountLockedException(customer.getLockedUntil());
        }

        long backoffSeconds = computeBackoffSeconds(customer, now, bf);
        if (backoffSeconds > 0) {
            auditService.record(AuditAction.LOGIN_BLOCKED_BACKOFF, AuditOutcome.FAILURE,
                    customer.getId(), deviceHash);
            anomalyDetector.recordFailure(AuthFailureKind.LOGIN, normalisedMsisdn);
            throw new BackoffActiveException(backoffSeconds);
        }

        // NO pooled connection is held across this line. That is the whole
        // reason this method carries no @Transactional.
        boolean pinMatches = pinHasher.matches(pin, customer.getPinHash());
        if (!pinMatches) {
            recordFailedAttempt(customer, bf, deviceHash, normalisedMsisdn);
            throw new InvalidCredentialsException();
        }

        // Mint FIRST: pure CPU, and the only remaining thrower between here and
        // the end. Minting before either write means a mint failure leaves zero
        // writes behind, instead of relying on a rollback that no longer exists.
        String accessToken = jwtIssuer.issue(new JwtIssuer.IssueRequest(
                customer.getId().toString(),
                customer.countryEnum(),
                customer.getKycTier(),
                CustomerScopes.DEFAULT,
                deviceHash,
                customer.getNationalIdHash()
        ));

        Instant settledAt = clock.instant();
        if (!lockoutStore.clearFailureCounters(customer.getId(), settledAt)) {
            // Unlike the failure branch, a vanished row here IS fatal: we must
            // not mint a session for a customer that no longer exists.
            throw new IllegalStateException("Customer " + customer.getId() + " disappeared during login");
        }

        // After the clear, so a failed clear can never strand a refresh_token row.
        RefreshToken refresh = refreshTokenService.issueNewFamily(customer.getId(), deviceHash);

        auditService.record(AuditAction.LOGIN_SUCCESS, AuditOutcome.SUCCESS,
                customer.getId(), deviceHash);

        return new LoginResult(accessToken, refresh.getSecret(), authProperties.accessTokenTtl());
    }

    /**
     * The wrong-PIN branch: one atomic statement, then audit, then the spray
     * detector — in that order, so the login_failure/account_locked row still
     * precedes any credential_spray_detected row in the audit chain.
     */
    private void recordFailedAttempt(Customer customer, AuthProperties.BruteForce bf,
                                     String deviceHash, String normalisedMsisdn) {
        // A FRESH instant, not the one captured before the hash. There is now a
        // deliberate 100-300ms CPU gap between them, and computeBackoffSeconds
        // measures elapsed time from last_failed_pin_at — writing the pre-hash
        // instant would hand the attacker back up to 300ms of every backoff
        // rung and shorten the lock by the same margin. Later can only ever
        // lengthen the penalty.
        Instant failedAt = clock.instant();

        OptionalInt attempts = lockoutStore.recordFailedAttempt(
                customer.getId(), failedAt, bf.maxFailedAttemptsBeforeLock(), bf.lockDuration());
        if (attempts.isEmpty()) {
            // Unreachable today (nothing deletes customers), and the response
            // must NOT change: the credentials were wrong, so a 500 here would
            // be both a worse answer and a new response-shape oracle.
            log.error("Customer {} vanished between lookup and failed-attempt write", customer.getId());
        }

        // From the RETURNED count, never from the in-memory customer — which is
        // stale by construction from the statement onward. This is the literal
        // predicate of the code it replaces, with the true post-increment value
        // substituted for the stale one, so the audit action and the row state
        // cannot diverge.
        AuditAction action = attempts.isPresent()
                && attempts.getAsInt() >= bf.maxFailedAttemptsBeforeLock()
                ? AuditAction.ACCOUNT_LOCKED
                : AuditAction.LOGIN_FAILURE;
        auditService.record(action, AuditOutcome.FAILURE, customer.getId(), deviceHash);

        anomalyDetector.recordFailure(AuthFailureKind.LOGIN, normalisedMsisdn);
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
