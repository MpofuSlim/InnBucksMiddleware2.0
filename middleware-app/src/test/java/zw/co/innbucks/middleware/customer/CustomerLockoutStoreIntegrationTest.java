package zw.co.innbucks.middleware.customer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The lockout statements themselves, driven directly — no HTTP, no Argon2id,
 * no gates. Everything about the brute-force ladder that can be proven without
 * timing is proven here.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class CustomerLockoutStoreIntegrationTest {

    @Autowired
    CustomerLockoutStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID customerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");
        customerId = seedCustomer(CustomerStatus.ACTIVE, 0, null, null);
    }

    private UUID seedCustomer(CustomerStatus status, int attempts, Instant lastFailed, Instant lockedUntil) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, status, failed_pin_attempts,
                     last_failed_pin_at, locked_until, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, "KE", "+2547" + Math.abs(id.getLeastSignificantBits() % 100000000L),
                "not-a-real-hash", KycTier.STANDARD.dbValue(), status.dbValue(), attempts,
                lastFailed == null ? null : Timestamp.from(lastFailed),
                lockedUntil == null ? null : Timestamp.from(lockedUntil),
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private <T> T column(UUID id, String name, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + name + " FROM customer WHERE id = ?", type, id);
    }

    /**
     * The premise the whole design rests on: {@code UPDATE … RETURNING} read
     * back through {@code JdbcTemplate.query}. There is NO precedent for
     * RETURNING anywhere in this repo, so this is checked before anything is
     * built on it rather than assumed to work.
     */
    @Test
    void theAtomicIncrementRoundTripsAndReturnsTheNewCount() {
        OptionalInt attempts = store.recordFailedAttempt(
                customerId, Instant.now(), 5, Duration.ofHours(1));

        assertThat(attempts).hasValue(1);
        assertThat(column(customerId, "failed_pin_attempts", Integer.class)).isEqualTo(1);
        assertThat(column(customerId, "last_failed_pin_at", Timestamp.class)).isNotNull();
        // Below the cap: nothing locked.
        assertThat(column(customerId, "status", String.class)).isEqualTo("active");
        assertThat(column(customerId, "locked_until", Timestamp.class)).isNull();
    }

    /**
     * THE lost-update fix. N concurrent callers must advance the counter by
     * exactly N and each be told a distinct value.
     *
     * <p>This is an INVARIANT assertion, not a race reproduction — it cannot
     * flake either way. Once the increment is one server-side statement, a
     * concurrent UPDATE of the same row blocks on the row-level lock rather
     * than proceeding on its own snapshot, then re-evaluates the SET
     * expressions against the newly-committed tuple; the WHERE is the primary
     * key alone, so that re-check can never turn false and drop an attempt.
     *
     * <p>On the read-modify-write this replaces, the result was 1, not N.
     */
    @Test
    void concurrentIncrementsAreNeverLost() throws Exception {
        int threads = 8;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ConcurrentLinkedQueue<Integer> returned = new ConcurrentLinkedQueue<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await(10, TimeUnit.SECONDS);
                    // Cap of 50 so the lock CASE never fires and the assertions
                    // stay about the counter alone.
                    store.recordFailedAttempt(customerId, Instant.now(), 50, Duration.ofHours(1))
                            .ifPresent(returned::add);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("all lockout writes finished (a hang must fail loudly, not time out the build)")
                .isTrue();

        assertThat(failures).isEmpty();
        assertThat(column(customerId, "failed_pin_attempts", Integer.class)).isEqualTo(threads);
        assertThat(new ArrayList<>(returned))
                .as("every caller is told its own post-increment value, no duplicates")
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    /**
     * The off-by-one, and the only test in the suite that catches it.
     * {@code failed_pin_attempts >= ?} instead of
     * {@code failed_pin_attempts + 1 >= ?} moves the production lock from the
     * 7th wrong PIN to the 8th while passing everything else.
     */
    @Test
    void theCapTransitionIsAtomicWithTheIncrement() {
        // Case A — the attempt that REACHES the cap locks, in the same statement.
        UUID atCap = seedCustomer(CustomerStatus.ACTIVE, 4, Instant.now(), null);

        assertThat(store.recordFailedAttempt(atCap, Instant.now(), 5, Duration.ofHours(1))).hasValue(5);
        assertThat(column(atCap, "status", String.class)).isEqualTo("locked");
        assertThat(column(atCap, "locked_until", Timestamp.class)).isNotNull();

        // Case B — one short of the cap does NOT lock.
        UUID belowCap = seedCustomer(CustomerStatus.ACTIVE, 3, Instant.now(), null);

        assertThat(store.recordFailedAttempt(belowCap, Instant.now(), 5, Duration.ofHours(1))).hasValue(4);
        assertThat(column(belowCap, "status", String.class)).isEqualTo("active");
        assertThat(column(belowCap, "locked_until", Timestamp.class)).isNull();
    }

    /** Clearing a lock must clear BOTH halves — never status without locked_until, or vice versa. */
    @Test
    void clearFailureCountersClearsBothHalvesOfTheLock() {
        Instant now = Instant.now();
        UUID locked = seedCustomer(CustomerStatus.LOCKED, 7, now.minusSeconds(600), now.plusSeconds(3600));

        assertThat(store.clearFailureCounters(locked, now)).isTrue();

        assertThat(column(locked, "failed_pin_attempts", Integer.class)).isZero();
        assertThat(column(locked, "last_failed_pin_at", Timestamp.class)).isNull();
        assertThat(column(locked, "locked_until", Timestamp.class)).isNull();
        assertThat(column(locked, "status", String.class)).isEqualTo("active");
    }

    /** The status CASE is narrow: it can only ever move 'locked' -> 'active'. */
    @Test
    void clearingCountersNeverWidensTheStatusVocabulary() {
        UUID pending = seedCustomer(CustomerStatus.PENDING_VERIFICATION, 2, Instant.now(), null);

        assertThat(store.clearFailureCounters(pending, Instant.now())).isTrue();

        assertThat(column(pending, "status", String.class)).isEqualTo("pending_verification");
        assertThat(column(pending, "failed_pin_attempts", Integer.class)).isZero();
    }

    /**
     * A row that is not there is DATA, not an exception — the property that
     * keeps a vanished customer from becoming a 500 and a new response-shape
     * oracle on the sign-in path.
     */
    @Test
    void aVanishedRowIsReportedNotThrown() {
        UUID ghost = UUID.randomUUID();

        assertThatCode(() -> {
            assertThat(store.recordFailedAttempt(ghost, Instant.now(), 5, Duration.ofHours(1))).isEmpty();
            assertThat(store.clearFailureCounters(ghost, Instant.now())).isFalse();
        }).doesNotThrowAnyException();
    }
}
