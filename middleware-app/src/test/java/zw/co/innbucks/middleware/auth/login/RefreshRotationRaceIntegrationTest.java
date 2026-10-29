package zw.co.innbucks.middleware.auth.login;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenReplayException;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.auth.refresh.RefreshToken;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two rotations of ONE refresh token that overlap — a client's double submit,
 * or an attacker racing the real holder with a stolen token and device hash.
 * Both read the row unrotated before either commits; the second's guarded
 * {@code markReplaced} then waits on the first's row lock, and Postgres
 * re-evaluates its WHERE against the committed row. Guarded on
 * {@code revoked_at} alone it still matched, so both answered 200: the family
 * forked into two live chains and no replay was ever detected. With
 * {@code replaced_by_jti IS NULL} in the guard the second is a replay, exactly
 * as it would be one request later. The interleaving is forced by holding the
 * first rotation's transaction open, as in {@code PinResetSignOutIntegrationTest}.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class RefreshRotationRaceIntegrationTest {

    private static final String MSISDN = "+254712345678";
    private static final String DEVICE = "test-device-1";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PinHasher pinHasher;
    @Autowired LoginService loginService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired PlatformTransactionManager transactionManager;

    UUID customerId;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");
        Instant now = Instant.now();
        customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', ?, ?, ?, 'FINERACT', ?, ?, 0, ?, ?)
                """,
                customerId, MSISDN, pinHasher.hash("1234"), KycTier.BASIC.dbValue(), "EXT-RACE",
                CustomerStatus.ACTIVE.dbValue(), Timestamp.from(now), Timestamp.from(now));
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void twoOverlappingRotationsOfOneTokenAreAReplayNeverAFork() throws Exception {
        String presented = loginService.login("0712345678", "1234", DEVICE).refreshToken();
        CountDownLatch firstRotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // rotate() joins this outer transaction: its mark and its successor
        // stay uncommitted, and the parent row stays locked, until release.
        Future<RefreshToken> first = pool.submit(() -> tx.execute(status -> {
            RefreshToken next = refreshTokenService.rotate(presented, DEVICE);
            firstRotated.countDown();
            await(release);
            return next;
        }));
        assertThat(firstRotated.await(10, TimeUnit.SECONDS)).isTrue();

        // Reads the row unrotated (the mark is not committed), passes every
        // check, and blocks in markReplaced on the first's row lock.
        Future<RefreshToken> second = pool.submit(() -> refreshTokenService.rotate(presented, DEVICE));
        awaitBlockedOnTheRowLock();
        assertThat(second.isDone()).isFalse();

        release.countDown();
        RefreshToken winner = first.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RefreshTokenReplayException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ?", Integer.class, customerId))
                .as("only the winner minted a successor").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, customerId))
                .as("the family is revoked, the winner's successor included").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM refresh_token WHERE jti = ?", String.class, winner.getJti()))
                .isEqualTo("replay_detected");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'refresh_replay_detected' AND customer_id = ?",
                Integer.class, customerId)).isEqualTo(1);
    }

    /** Waits until a backend is waiting on a lock inside the guarded mark. */
    private void awaitBlockedOnTheRowLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database()
                       AND wait_event_type = 'Lock'
                       AND query LIKE '%SET replaced_by_jti%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the second rotation never waited on the first's row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch never released");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
