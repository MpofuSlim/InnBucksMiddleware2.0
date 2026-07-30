package zw.co.innbucks.middleware.auth.refresh;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenInvalidException;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenReplayException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String INSERT_SQL = """
            INSERT INTO refresh_token (jti, token_hash, customer_id, family_id, device_hash, issued_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String PRUNE_SQL = """
            DELETE FROM refresh_token
             WHERE expires_at < ?
            """;

    /**
     * How long after a refresh token's expiry we keep its row around. Tokens
     * are invalid the moment {@code expires_at} passes — the post-expiry
     * grace window is purely forensic ("which session was active at time X")
     * for incident investigation. Seven days is plenty to cover a typical
     * "we noticed Monday morning" follow-up on a Friday-night anomaly,
     * without letting the table grow without bound.
     */
    private static final int FORENSIC_RETENTION_DAYS = 7;

    private final RefreshTokenRepository repository;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public RefreshToken issueNewFamily(UUID customerId, String deviceHash) {
        return persist(customerId, UUID.randomUUID(), deviceHash);
    }

    @Transactional
    public RefreshToken rotate(String presentedSecret, String deviceHash) {
        Optional<RefreshToken> maybe = repository.findByTokenHash(sha256HexLower(presentedSecret));
        if (maybe.isEmpty()) {
            throw new RefreshTokenInvalidException();
        }

        RefreshToken current = maybe.get();
        Instant now = clock.instant();

        if (current.getRevokedAt() != null || current.getExpiresAt().isBefore(now)) {
            throw new RefreshTokenInvalidException();
        }

        if (current.getReplacedByJti() != null) {
            repository.revokeFamily(current.getFamilyId(), now, "replay_detected");
            auditService.record(
                    AuditAction.REFRESH_REPLAY_DETECTED,
                    AuditOutcome.FAILURE,
                    current.getCustomerId(),
                    deviceHash);
            throw new RefreshTokenReplayException();
        }

        RefreshToken next = persist(current.getCustomerId(), current.getFamilyId(), deviceHash);
        current.setReplacedByJti(next.getJti());
        repository.save(current);

        auditService.record(
                AuditAction.REFRESH_SUCCESS,
                AuditOutcome.SUCCESS,
                current.getCustomerId(),
                deviceHash);

        return next;
    }

    @Transactional
    public Optional<RefreshToken> revokeFamily(String presentedSecret, String reason) {
        Optional<RefreshToken> maybe = repository.findByTokenHash(sha256HexLower(presentedSecret));
        maybe.ifPresent(token ->
                repository.revokeFamily(token.getFamilyId(), clock.instant(), reason));
        return maybe;
    }

    /**
     * Daily prune of refresh_token rows whose expires_at passed more than
     * {@link #FORENSIC_RETENTION_DAYS} days ago. Every successful refresh
     * inserts a new row + leaves the old one in place (used for replay
     * detection), so the table grows linearly with sessions × customers
     * until it's pruned. Without this job the table would dominate the DB
     * after a few months in a busy market.
     *
     * <p>Runs at 03:30 server-time daily. Single-replica deploy assumption
     * holds for now (this middleware deploys one-per-country); if we ever
     * scale horizontally within a country, wrap this with ShedLock so only
     * one pod runs each tick. The deferred idempotency_record +
     * otp_challenge pruners (per CLAUDE.md) belong on the same schedule.
     */
    @Scheduled(cron = "${innbucks.auth.refresh-token-prune-cron:0 30 3 * * *}")
    public void pruneExpiredRefreshTokens() {
        Instant cutoff = clock.instant().minus(FORENSIC_RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = jdbcTemplate.update(PRUNE_SQL, Timestamp.from(cutoff));
        log.info("Pruned {} refresh_token row(s) with expires_at < {} ({} day retention past expiry)",
                deleted, cutoff, FORENSIC_RETENTION_DAYS);
    }

    private RefreshToken persist(UUID customerId, UUID familyId, String deviceHash) {
        // INSERT via JdbcTemplate: Spring Data JDBC's CrudRepository.save() treats
        // an entity with a manually-assigned @Id as already-existing and emits UPDATE
        // that affects zero rows, so the new row never lands in the DB.
        UUID jti = UUID.randomUUID();
        // The client secret is a fresh random UUID, separate from the internal jti:
        // a DB read yields only token_hash, never a usable token. ~122 bits of entropy
        // makes unsalted SHA-256 at rest safe (no brute force / rainbow table).
        UUID secret = UUID.randomUUID();
        String tokenHash = sha256HexLower(secret.toString());
        Instant now = clock.instant();
        Instant expiresAt = now.plus(authProperties.refreshTokenTtl());

        jdbcTemplate.update(INSERT_SQL,
                jti,
                tokenHash,
                customerId,
                familyId,
                deviceHash,
                Timestamp.from(now),
                Timestamp.from(expiresAt));

        RefreshToken token = new RefreshToken();
        token.setJti(jti);
        token.setTokenHash(tokenHash);
        token.setSecret(secret.toString());
        token.setCustomerId(customerId);
        token.setFamilyId(familyId);
        token.setDeviceHash(deviceHash);
        token.setIssuedAt(now);
        token.setExpiresAt(expiresAt);
        return token;
    }

    /**
     * SHA-256 of the UTF-8 bytes, rendered as lowercase hex (64 chars). The
     * opaque refresh-token secret carries ~122 bits of entropy, so an unsalted
     * digest at rest is not brute-forceable and has no rainbow-table exposure.
     */
    private static String sha256HexLower(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JCA spec on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
