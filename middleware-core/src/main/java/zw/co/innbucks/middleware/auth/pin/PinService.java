package zw.co.innbucks.middleware.auth.pin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.exception.PinSetNotAllowedException;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.otp.ConsumedVerificationTokenStore;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.VerificationTokenVerifier;
import zw.co.innbucks.middleware.otp.VerifiedToken;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Consumes the verification token from {@code /auth/otp/verify} and either
 * sets a PIN for the first time (PENDING_VERIFICATION → ACTIVE) or resets an
 * existing one (ACTIVE customer who forgot their PIN).
 *
 * The verification token's audience + purpose claim is the authentication —
 * the customer doesn't carry a Bearer JWT for this flow because they
 * literally don't have one yet (or have lost the ability to use the old one).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinService {

    private static final String UPDATE_SQL = """
            UPDATE customer
               SET pin_hash = ?,
                   status = ?,
                   failed_pin_attempts = 0,
                   last_failed_pin_at = NULL,
                   locked_until = NULL,
                   updated_at = ?
             WHERE id = ?
            """;

    private final VerificationTokenVerifier verificationTokenVerifier;
    private final ConsumedVerificationTokenStore consumedVerificationTokenStore;
    private final CustomerRepository customerRepository;
    private final PinHasher pinHasher;
    private final AuditService auditService;
    private final CountryProperties countryProperties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Transactional
    public void setInitialPin(String verificationToken, String pin) {
        apply(verificationToken, pin, OtpPurpose.PIN_SETUP);
    }

    @Transactional
    public void resetPin(String verificationToken, String pin) {
        apply(verificationToken, pin, OtpPurpose.PIN_RESET);
    }

    private void apply(String verificationToken, String pin, OtpPurpose expectedPurpose) {
        if (!PinFormat.isValid(pin)) {
            // Same surface for either flow — never tell the caller the PIN format was wrong
            // separately from the verification-token check, to keep error surfaces uniform.
            throw new PinSetNotAllowedException("invalid pin format");
        }

        VerifiedToken verified = verificationTokenVerifier.verify(verificationToken, expectedPurpose);

        // Single-use: record the jti before doing any work. On a replay this throws
        // VerificationTokenInvalidException(REPLAYED) and the @Transactional method
        // rolls back (nothing committed). On success the consume INSERT and the PIN
        // UPDATE commit together — the token is consumed iff the PIN op succeeds, so
        // a later replay of the same token then fails.
        consumedVerificationTokenStore.consume(verified.jti(), verified.expiresAt());

        String country = countryProperties.country().name();
        Optional<Customer> maybe = customerRepository.findByCountryAndMsisdn(country, verified.msisdn());
        if (maybe.isEmpty()) {
            // Token is cryptographically valid but the customer record has disappeared
            // — e.g. between OTP verify and pin-set the row was deleted. Treat as not-allowed.
            log.warn("Verification token valid but customer not found country={} msisdn={}",
                    country, MsisdnMasking.mask(verified.msisdn()));
            throw new PinSetNotAllowedException("customer not found");
        }
        Customer customer = maybe.get();

        // State machine: SETUP requires PENDING_VERIFICATION; RESET requires ACTIVE.
        CustomerStatus current = customer.statusEnum();
        switch (expectedPurpose) {
            case PIN_SETUP -> {
                if (current != CustomerStatus.PENDING_VERIFICATION) {
                    auditService.record(AuditAction.PIN_SET_REJECTED, AuditOutcome.FAILURE,
                            customer.getId(), null);
                    throw new PinSetNotAllowedException(
                            "PIN_SETUP requires PENDING_VERIFICATION; customer is " + current);
                }
            }
            case PIN_RESET -> {
                if (current != CustomerStatus.ACTIVE && current != CustomerStatus.LOCKED) {
                    auditService.record(AuditAction.PIN_SET_REJECTED, AuditOutcome.FAILURE,
                            customer.getId(), null);
                    throw new PinSetNotAllowedException(
                            "PIN_RESET requires ACTIVE or LOCKED; customer is " + current);
                }
            }
        }

        Instant now = clock.instant();
        int updated = jdbcTemplate.update(UPDATE_SQL,
                pinHasher.hash(pin),
                CustomerStatus.ACTIVE.dbValue(),
                Timestamp.from(now),
                customer.getId());

        if (updated != 1) {
            throw new IllegalStateException("Customer update affected " + updated + " rows (expected 1)");
        }

        AuditAction action = switch (expectedPurpose) {
            case PIN_SETUP -> AuditAction.PIN_SET;
            case PIN_RESET -> AuditAction.PIN_RESET;
        };
        auditService.record(action, AuditOutcome.SUCCESS, customer.getId(), null);
        log.info("PIN {} for customer id={} status_before={} status_after=ACTIVE",
                expectedPurpose, customer.getId(), current);
    }
}
