package zw.co.innbucks.middleware.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.MsisdnMasking;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies one-time codes. Pattern: only one active challenge per
 * (msisdn, purpose) at a time — requesting a new one supersedes any earlier
 * outstanding one. Each challenge tracks attempts and expires; both states
 * are checked before accepting a code.
 *
 * Codes are stored as keyed HMAC-SHA256 hex (see {@link OtpHasher} — a bare
 * hash of a 6-digit space is trivially reversible), never plaintext, and
 * compared with constant-time MessageDigest.isEqual to defeat timing attacks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final SecureRandom RNG = new SecureRandom();

    private static final String INSERT_SQL = """
            INSERT INTO otp_challenge
                (id, country, msisdn, purpose, code_hash, attempts, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?)
            """;

    private static final String SUPERSEDE_SQL = """
            UPDATE otp_challenge
               SET superseded_at = ?
             WHERE msisdn = ?
               AND purpose = ?
               AND consumed_at IS NULL
               AND superseded_at IS NULL
            """;

    private static final String SELECT_ACTIVE_SQL = """
            SELECT id, code_hash, attempts, expires_at
              FROM otp_challenge
             WHERE msisdn = ?
               AND purpose = ?
               AND consumed_at IS NULL
               AND superseded_at IS NULL
             ORDER BY issued_at DESC
             LIMIT 1
            """;

    private static final String INCREMENT_ATTEMPTS_SQL = """
            UPDATE otp_challenge
               SET attempts = attempts + 1
             WHERE id = ?
            """;

    private static final String MARK_CONSUMED_SQL = """
            UPDATE otp_challenge
               SET consumed_at = ?
             WHERE id = ?
            """;

    private static final String PRUNE_SQL = """
            DELETE FROM otp_challenge
             WHERE expires_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final OtpHasher otpHasher;
    private final OtpProperties otpProperties;
    private final MsisdnNormalizerRegistry msisdnRegistry;
    private final CountryProperties countryProperties;
    private final SmsSender smsSender;
    private final VerificationTokenIssuer verificationTokenIssuer;
    private final AuditService auditService;
    private final Clock clock;
    private final RateLimiterService rateLimiterService;

    @Transactional
    public void request(String rawMsisdn, OtpPurpose purpose) {
        String msisdn = msisdnRegistry.forDeployment().normalize(rawMsisdn);
        // Per-MSISDN SMS-flood guard. Throws RateLimitExceededException (-> 429)
        // BEFORE any DB write or SMS dispatch, so a flood for one number costs
        // nothing. Keyed on the normalized MSISDN so format variants share a bucket.
        rateLimiterService.enforceMsisdnOtpRequest(msisdn);
        Instant now = clock.instant();

        // Invalidate any prior outstanding challenge for this (msisdn, purpose).
        jdbcTemplate.update(SUPERSEDE_SQL, Timestamp.from(now), msisdn, purpose.name());

        String code = generateCode();
        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(otpProperties.ttl());

        jdbcTemplate.update(INSERT_SQL,
                id,
                countryProperties.country().name(),
                msisdn,
                purpose.name(),
                otpHasher.hash(code),
                Timestamp.from(now),
                Timestamp.from(expiresAt));

        smsSender.send(msisdn, smsBody(code, purpose));
        auditService.record(AuditAction.OTP_REQUESTED, AuditOutcome.SUCCESS, null, null);
        // MSISDN masked at the log site — every centralised log shipper
        // (ECS, Loki, etc.) ends up with the value at REST, so passing the
        // raw value through INFO turns the log into PII storage. MsisdnMasking
        // keeps the country code + last 3 digits for support correlation.
        log.info("OTP issued msisdn={} purpose={} expiresAt={}",
                MsisdnMasking.mask(msisdn), purpose, expiresAt);
    }

    /**
     * Note the {@code noRollbackFor}: throwing {@link OtpVerificationException}
     * is the normal "wrong code" path. We MUST keep the attempts-counter
     * increment durable so the next call sees attempts=N+1; without this Spring
     * would roll back the increment alongside the throw and the customer could
     * brute-force forever with the counter stuck at zero.
     */
    @Transactional(noRollbackFor = OtpVerificationException.class)
    public VerificationToken verify(String rawMsisdn, OtpPurpose purpose, String code) {
        String msisdn = msisdnRegistry.forDeployment().normalize(rawMsisdn);

        Optional<ActiveChallenge> maybe = loadActive(msisdn, purpose);
        if (maybe.isEmpty()) {
            auditService.record(AuditAction.OTP_VERIFY_FAILED, AuditOutcome.FAILURE, null, null);
            throw new OtpVerificationException(OtpVerificationException.Reason.NO_ACTIVE_CHALLENGE);
        }

        ActiveChallenge ch = maybe.get();
        Instant now = clock.instant();

        if (ch.expiresAt().isBefore(now)) {
            auditService.record(AuditAction.OTP_VERIFY_FAILED, AuditOutcome.FAILURE, null, null);
            throw new OtpVerificationException(OtpVerificationException.Reason.EXPIRED);
        }
        if (ch.attempts() >= otpProperties.maxAttempts()) {
            jdbcTemplate.update(SUPERSEDE_SQL, Timestamp.from(now), msisdn, purpose.name());
            auditService.record(AuditAction.OTP_VERIFY_FAILED, AuditOutcome.FAILURE, null, null);
            throw new OtpVerificationException(OtpVerificationException.Reason.ATTEMPTS_EXHAUSTED);
        }

        jdbcTemplate.update(INCREMENT_ATTEMPTS_SQL, ch.id());

        if (!constantTimeEquals(ch.codeHash(), otpHasher.hash(code))) {
            auditService.record(AuditAction.OTP_VERIFY_FAILED, AuditOutcome.FAILURE, null, null);
            throw new OtpVerificationException(OtpVerificationException.Reason.CODE_MISMATCH);
        }

        jdbcTemplate.update(MARK_CONSUMED_SQL, Timestamp.from(now), ch.id());
        auditService.record(AuditAction.OTP_VERIFIED, AuditOutcome.SUCCESS, null, null);
        return verificationTokenIssuer.issue(msisdn, purpose);
    }

    /**
     * Daily prune of otp_challenge rows past their {@code expires_at} — expired,
     * consumed-then-expired, and superseded-then-expired alike. Challenges are
     * short-lived (minutes) and high-volume (one per request); the forensic
     * trail lives in audit_event, not here. Active (pre-expiry) challenges are
     * never touched. Uses idx_otp_expires_at (V7). Runs at 03:40 server-time.
     */
    @Scheduled(cron = "${innbucks.otp.prune-cron:0 40 3 * * *}")
    public void pruneExpiredOtpChallenges() {
        int deleted = jdbcTemplate.update(PRUNE_SQL, Timestamp.from(clock.instant()));
        log.info("Pruned {} expired otp_challenge row(s)", deleted);
    }

    private Optional<ActiveChallenge> loadActive(String msisdn, OtpPurpose purpose) {
        var rows = jdbcTemplate.query(SELECT_ACTIVE_SQL,
                (rs, n) -> new ActiveChallenge(
                        (UUID) rs.getObject("id"),
                        rs.getString("code_hash"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("expires_at").toInstant()),
                msisdn, purpose.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private String generateCode() {
        String fixed = otpProperties.fixedCode();
        if (fixed != null && !fixed.isBlank()) {
            return fixed;
        }
        // Random 6-digit, zero-padded.
        int n = RNG.nextInt(1_000_000);
        return String.format("%06d", n);
    }

    private static String smsBody(String code, OtpPurpose purpose) {
        return "Your Innbucks " + switch (purpose) {
            case PIN_SETUP -> "PIN setup";
            case PIN_RESET -> "PIN reset";
        } + " code is " + code + ". Do not share it with anyone.";
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private record ActiveChallenge(UUID id, String codeHash, int attempts, Instant expiresAt) {
    }
}
