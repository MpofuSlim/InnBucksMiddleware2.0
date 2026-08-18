package zw.co.innbucks.middleware.auth.login;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.auth.exception.InvalidCredentialsException;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the SERVICE actually wires the atomic store — not merely that the SQL
 * is atomic, which {@code CustomerLockoutStoreIntegrationTest} covers.
 *
 * <p>On the read-modify-write this replaces, the ~100-300ms Argon2id compare
 * sat between the customer read and the counter write, so barrier-released
 * threads all read the same N long before the first write landed and the
 * counter advanced by 1 instead of by N. That is the bug: an attacker firing
 * concurrent guesses never moves the account toward its lock.
 *
 * <p>SEPARATE CLASS, and both property overrides are load-bearing:
 * <ul>
 *   <li>{@code backoff-base=0s} makes {@code min(0 << shift, 60) == 0} for
 *       every attempt count, so no thread can be refused by the backoff gate
 *       under ANY interleaving. That converts the expected result from a
 *       timing prediction into an invariant — the test cannot flake.</li>
 *   <li>{@code max-failed-attempts-before-lock=50} stops the concurrent
 *       failures flipping the row to LOCKED, which would change both the
 *       expected status and the audit action.</li>
 * </ul>
 * They live here rather than on {@code LoginFlowIntegrationTest} because an
 * override there would change the cap for its existing cases. The cost is a
 * second Spring context (and, without Testcontainers reuse enabled, a second
 * postgres container).
 */
@SpringBootTest(properties = {
        "innbucks.auth.brute-force.backoff-base=0s",
        "innbucks.auth.brute-force.max-failed-attempts-before-lock=50"
})
@Import(PostgresTestContainer.class)
class LoginLockoutConcurrencyIntegrationTest {

    private static final String TEST_MSISDN = "+254712345678";

    @Autowired
    LoginService loginService;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID customerId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");

        Instant now = Instant.now();
        customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                customerId, "KE", TEST_MSISDN, pinHasher.hash("1234"), KycTier.STANDARD.dbValue(),
                "FINERACT", "TEST-EXT-CONC", CustomerStatus.ACTIVE.dbValue(), 0,
                Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * Four simultaneous wrong PINs must count four.
     *
     * <p>Driven through {@code LoginService} directly rather than MockMvc:
     * MockHttpServletRequest is not documented thread-safe, and off-request the
     * audit service simply records no request metadata while the anomaly
     * detector short-circuits — so the rows still land and stay countable.
     *
     * <p>N is kept at 4 because each in-flight login still borrows a connection
     * for its audit unit and every audit writer serialises on
     * {@code audit_chain_head}'s row lock; the test profile sets no Hikari keys,
     * so Boot's default pool applies.
     */
    @Test
    void concurrentWrongPinsAreAllCounted() throws Exception {
        int threads = 4;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await(10, TimeUnit.SECONDS);
                    loginService.login("0712345678", "9999", "test-device-conc");
                } catch (InvalidCredentialsException expected) {
                    // The point of the test — every thread must get here.
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("all concurrent logins finished (a hang must fail loudly, not time out the build)")
                .isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM customer WHERE id = ?", Integer.class, customerId))
                .as("K racing failures must count K — on the read-modify-write this was 1")
                .isEqualTo(threads);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'login_failure'", Integer.class))
                .isEqualTo(threads);
    }
}
