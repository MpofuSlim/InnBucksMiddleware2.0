package zw.co.innbucks.middleware.otp;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link ConsumedVerificationTokenStore} with a mocked
 * {@link JdbcTemplate}: no Spring context, no Docker. The single-use guard is
 * the primary-key conflict on a second consume of the same jti, which Postgres
 * surfaces (via Spring's translation) as {@link DuplicateKeyException}.
 */
class ConsumedVerificationTokenStoreTest {

    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ConsumedVerificationTokenStore store =
            new ConsumedVerificationTokenStore(jdbcTemplate, clock);

    @Test
    void firstConsumeInsertsRow() {
        String jti = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(300);

        store.consume(jti, expiresAt);

        verify(jdbcTemplate).update(
                contains("INSERT INTO consumed_verification_token"),
                eq(UUID.fromString(jti)),
                eq(Timestamp.from(now)),
                eq(Timestamp.from(expiresAt)));
    }

    @Test
    void replayedConsumeThrowsReplayed() {
        String jti = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(300);
        // Simulate the second consume of the same jti hitting the PK conflict.
        doThrow(new DuplicateKeyException("duplicate key value violates unique constraint"))
                .when(jdbcTemplate).update(contains("INSERT INTO consumed_verification_token"),
                        any(), any(), any());

        assertThatThrownBy(() -> store.consume(jti, expiresAt))
                .isInstanceOf(VerificationTokenInvalidException.class)
                .extracting(e -> ((VerificationTokenInvalidException) e).getReason())
                .isEqualTo(VerificationTokenInvalidException.Reason.REPLAYED);
    }

    @Test
    void pruneDeletesRowsExpiredBeforeNow() {
        when(jdbcTemplate.update(contains("DELETE FROM consumed_verification_token"), any(Object[].class)))
                .thenReturn(3);

        store.pruneExpired();

        // Cutoff = now (no forensic grace — past exp the token's own claim rejects it).
        verify(jdbcTemplate).update(
                contains("DELETE FROM consumed_verification_token"),
                eq(Timestamp.from(now)));
    }
}
