package zw.co.innbucks.middleware.auth.refresh;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
     * The two locks that make "sign out everywhere" race-free against a
     * rotation in flight. They CONFLICT with each other (SHARE vs NO KEY
     * UPDATE) and with nothing else that matters: concurrent rotations of one
     * customer take SHARE together, and the FK checks behind every
     * refresh_token / ledger_transaction INSERT take KEY SHARE, which neither
     * blocks. Never FOR UPDATE on the revoke side (it would queue those
     * INSERTs) — same reasoning as the deferred login re-read in CLAUDE.md.
     */
    private static final String LOCK_CUSTOMER_FOR_ROTATION_SQL = """
            SELECT id FROM customer WHERE id = ? FOR SHARE
            """;

    private static final String LOCK_CUSTOMER_FOR_REVOKE_ALL_SQL = """
            SELECT id FROM customer WHERE id = ? FOR NO KEY UPDATE
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
        return persist(UUID.randomUUID(), customerId, UUID.randomUUID(), deviceHash);
    }

    /**
     * Whether the presented token would rotate right now — known, unrevoked,
     * unexpired, not yet replaced, presented on its own device — and if so,
     * whose it is. A single read: no lock, no transaction held, NOTHING spent
     * and nothing revoked. Empty for every other case.
     *
     * <p>It exists so {@code /auth/refresh} can ask the core about a bank block
     * BEFORE the token is spent: a slow core then costs the client a slow
     * answer, never its session (an unspent token can simply be presented
     * again; a spent one whose successor never arrived reads as a replay and
     * kills the family). It decides nothing on its own: {@link #rotate} still
     * re-checks every condition, under its lock, and is the ONE place a replay
     * or a device mismatch revokes the family and is audited — so a caller that
     * gets empty here just calls {@link #rotate} and lets it refuse.
     */
    public Optional<UUID> liveSessionCustomer(String presentedSecret, String deviceHash) {
        return repository.findByTokenHash(sha256HexLower(presentedSecret))
                .filter(token -> token.getRevokedAt() == null)
                .filter(token -> !token.getExpiresAt().isBefore(clock.instant()))
                .filter(token -> token.getReplacedByJti() == null)
                .filter(token -> sameDevice(token.getDeviceHash(), deviceHash))
                .map(RefreshToken::getCustomerId);
    }

    /**
     * The {@code noRollbackFor} is load-bearing: replay detection and device
     * mismatch REVOKE THE WHOLE FAMILY and then throw so the caller 401s.
     * Without it, Spring rolls the revocation back together with the throw and
     * the family stays alive — the attacker's next attempt starts fresh. Same
     * pattern as {@code OtpService.verify}'s attempts counter. (Audit rows are
     * REQUIRES_NEW and were never at risk.)
     */
    @Transactional(noRollbackFor = {RefreshTokenInvalidException.class, RefreshTokenReplayException.class})
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
            throw replayDetected(current, now, deviceHash);
        }

        // Device binding (auth slice 4): the refresh token only rotates on the
        // device it was issued to. A mismatch means the opaque secret left that
        // device — theft, not a retry — so the WHOLE family is revoked, same
        // posture as replay detection. The response is the generic
        // refresh_invalid 401 (via RefreshTokenInvalidException): an attacker
        // probing with a stolen token learns nothing about WHY it died, while
        // the legitimate app just routes to a fresh login. Constant-time
        // compare; a NULL stored hash cannot occur (login always binds one)
        // but is treated as a mismatch — fail closed, not open.
        if (!sameDevice(current.getDeviceHash(), deviceHash)) {
            repository.revokeFamily(current.getFamilyId(), now, "device_mismatch");
            auditService.record(
                    AuditAction.REFRESH_DEVICE_MISMATCH,
                    AuditOutcome.FAILURE,
                    current.getCustomerId(),
                    deviceHash);
            throw new RefreshTokenInvalidException();
        }

        // Serialise against revokeAllSessions (PIN set/reset). Without it a
        // rotation straddling the revocation survives it: the revoking UPDATE
        // reads from a snapshot taken when it STARTS, so a successor this
        // transaction inserts and commits after that moment is never seen —
        // and a client refreshing in a loop is inside that window most of the
        // time. Holding SHARE on the customer row means either the revocation
        // committed before this line (markReplaced below then sees it) or it
        // waits for this commit and then sees the successor.
        jdbcTemplate.queryForList(LOCK_CUSTOMER_FOR_ROTATION_SQL, current.getCustomerId());

        // Mark the presented row replaced BEFORE inserting its successor, and
        // only while it is still unrevoked and unrotated: a revocation (logout,
        // replay, sign-out-everywhere) or another rotation of the same token
        // that committed after the read above must win, and the successor
        // must not exist if it does — this method's noRollbackFor would
        // otherwise commit an orphan.
        UUID successorJti = UUID.randomUUID();
        if (repository.markReplaced(current.getJti(), successorJti) != 1) {
            throw lostTheRowAfterReadingIt(current, now, deviceHash);
        }
        RefreshToken next = persist(successorJti, current.getCustomerId(), current.getFamilyId(), deviceHash);

        auditService.record(
                AuditAction.REFRESH_SUCCESS,
                AuditOutcome.SUCCESS,
                current.getCustomerId(),
                deviceHash);

        return next;
    }

    /**
     * The presented row changed between rotation's read and its guarded mark.
     * Re-read it (READ COMMITTED: this statement sees what committed in
     * between) and judge it exactly as the opening checks would have: revoked
     * first — a revocation is not a theft signal — then rotated, which is a
     * REPLAY. That second case is two requests presenting the same token at
     * once — a client's double submit, or an attacker racing the real holder
     * with a stolen token and device hash — and is treated exactly like the
     * same two requests one after the other: the family is revoked, the
     * successor the winner just minted included, and audited.
     */
    private RuntimeException lostTheRowAfterReadingIt(RefreshToken read, Instant now, String deviceHash) {
        Optional<RefreshToken> reread = repository.findById(read.getJti());
        if (reread.isEmpty() || reread.get().getRevokedAt() != null) {
            return new RefreshTokenInvalidException();
        }
        if (reread.get().getReplacedByJti() != null) {
            return replayDetected(read, now, deviceHash);
        }
        // Unreachable: the row is unrevoked and unrotated, so the guarded
        // UPDATE would have matched it. Refuse rather than guess.
        log.warn("Refresh rotation could not mark token jti={} although it is live; refusing", read.getJti());
        return new RefreshTokenInvalidException();
    }

    /**
     * A token presented after it was rotated away: its secret has left the
     * device that rotated it, or it is being raced. Revokes the WHOLE family
     * and audits it; the caller throws what this returns (the
     * {@code noRollbackFor} on {@link #rotate} keeps the revocation).
     */
    private RefreshTokenReplayException replayDetected(RefreshToken token, Instant now, String deviceHash) {
        repository.revokeFamily(token.getFamilyId(), now, "replay_detected");
        auditService.record(
                AuditAction.REFRESH_REPLAY_DETECTED,
                AuditOutcome.FAILURE,
                token.getCustomerId(),
                deviceHash);
        return new RefreshTokenReplayException();
    }

    @Transactional
    public Optional<RefreshToken> revokeFamily(String presentedSecret, String reason) {
        Optional<RefreshToken> maybe = repository.findByTokenHash(sha256HexLower(presentedSecret));
        maybe.ifPresent(token ->
                repository.revokeFamily(token.getFamilyId(), clock.instant(), reason));
        return maybe;
    }

    /**
     * Sign out everywhere: revokes every refresh-token family the customer
     * holds, in the CALLER's transaction (MANDATORY — the revocation must
     * commit or roll back with the change that motivates it, e.g. the new
     * PIN). Returns the number of rows revoked (a family's rotated ancestors
     * count too; 0 = nothing was signed in).
     *
     * <p>Takes the customer row {@code FOR NO KEY UPDATE} first, which
     * conflicts with the {@code FOR SHARE} every {@link #rotate} takes before
     * minting a successor — see there for why that ordering is the guarantee.
     * Access tokens already issued are NOT revoked: they are self-contained
     * JWTs and live out their TTL (10 minutes by default).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllSessions(UUID customerId, String reason) {
        jdbcTemplate.queryForList(LOCK_CUSTOMER_FOR_REVOKE_ALL_SQL, customerId);
        return repository.revokeAllForCustomer(customerId, clock.instant(), reason);
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

    private RefreshToken persist(UUID jti, UUID customerId, UUID familyId, String deviceHash) {
        // INSERT via JdbcTemplate: Spring Data JDBC's CrudRepository.save() treats
        // an entity with a manually-assigned @Id as already-existing and emits UPDATE
        // that affects zero rows, so the new row never lands in the DB.
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
     * The device binding, in ONE place so {@link #liveSessionCustomer} and
     * {@link #rotate} can never disagree about it. Constant-time; a null on
     * either side is a mismatch (fail closed).
     */
    private static boolean sameDevice(String storedDeviceHash, String presentedDeviceHash) {
        return storedDeviceHash != null && presentedDeviceHash != null
                && MessageDigest.isEqual(
                        storedDeviceHash.getBytes(StandardCharsets.UTF_8),
                        presentedDeviceHash.getBytes(StandardCharsets.UTF_8));
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
