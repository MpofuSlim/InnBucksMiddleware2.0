package zw.co.innbucks.middleware.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Enforces single-use of PIN-setup / PIN-reset verification tokens. The token
 * itself is a stateless JWT — nothing stops the same valid token from driving a
 * PIN set/reset repeatedly within its short TTL. This store closes that replay
 * window by recording the token's {@code jti} the first time it is consumed; a
 * later attempt with the same {@code jti} hits the primary-key conflict and is
 * rejected as {@link VerificationTokenInvalidException.Reason#REPLAYED}.
 *
 * <p>The INSERT goes via {@link JdbcTemplate} directly (not a repository save):
 * Spring Data JDBC's {@code CrudRepository.save()} treats an entity with a
 * manually-assigned {@code @Id} as already-existing and emits UPDATE — the row
 * never lands, defeating the conflict-based replay guard. Same gotcha as
 * {@code RefreshTokenService.persist}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumedVerificationTokenStore {

    private static final String INSERT_SQL = """
            INSERT INTO consumed_verification_token (jti, consumed_at, expires_at)
            VALUES (?, ?, ?)
            """;

    private static final String PRUNE_SQL = """
            DELETE FROM consumed_verification_token
             WHERE expires_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /**
     * Records {@code jti} as consumed. The first call for a given {@code jti}
     * succeeds; any subsequent call collides on the primary key and throws
     * {@link VerificationTokenInvalidException} with reason
     * {@link VerificationTokenInvalidException.Reason#REPLAYED}.
     *
     * <p>Called inside the PIN-set/reset transaction, so on success the
     * consume row and the PIN UPDATE commit atomically — the {@code jti} is
     * marked consumed iff the PIN op succeeds, and a later replay then fails.
     *
     * @param jti       the token's JWT ID (UUID string)
     * @param expiresAt the token's {@code exp} — when the row becomes prunable
     */
    public void consume(String jti, Instant expiresAt) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    UUID.fromString(jti),
                    Timestamp.from(clock.instant()),
                    Timestamp.from(expiresAt));
        } catch (DuplicateKeyException ex) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.REPLAYED,
                    "verification token already used");
        }
    }

    /**
     * Daily prune of consumed-token rows whose expires_at has passed. Once a
     * token's own {@code exp} claim is in the past the verifier rejects it on
     * the expiry check anyway, so the consume row is no longer needed for
     * replay defence and only serves to bloat the table.
     *
     * <p>Runs at 03:35 server-time daily (just after the refresh-token prune at
     * 03:30). Single-replica deploy assumption holds — this middleware
     * deploys one-per-country; wrap with ShedLock if we ever scale a country
     * horizontally.
     */
    // 03:37, not 03:35: the idempotency prune already owns 03:35, and two
    // DELETE sweeps firing on the same tick on a single-replica cell contend
    // for the same pool and I/O for no reason. The nightly jobs are spaced at
    // 03:30 / 03:35 / 03:37 / 03:40 / 03:45 so each finishes before the next.
    @Scheduled(cron = "${innbucks.auth.consumed-verification-token-prune-cron:0 37 3 * * *}")
    public void pruneExpired() {
        Instant cutoff = clock.instant();
        int deleted = jdbcTemplate.update(PRUNE_SQL, Timestamp.from(cutoff));
        log.info("Pruned {} consumed_verification_token row(s) with expires_at < {}", deleted, cutoff);
    }
}
