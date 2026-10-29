package zw.co.innbucks.middleware.auth.pin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.exception.RefreshTokenInvalidException;
import zw.co.innbucks.middleware.auth.login.LoginService;
import zw.co.innbucks.middleware.auth.refresh.RefreshToken;
import zw.co.innbucks.middleware.auth.refresh.RefreshTokenService;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.VerificationTokenIssuer;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A PIN reset signs the customer out everywhere — including a session that
 * is mid-rotation at the moment of the reset. The two concurrency cases hold a
 * transaction open on one side to force the interleaving deterministically:
 *
 * <ul>
 *   <li>rotation first: its successor commits AFTER the reset's revoking
 *       statement would have taken its snapshot. Only the customer-row lock
 *       (SHARE vs NO KEY UPDATE) makes the reset wait and see it; without the
 *       lock the successor survives and {@link #liveRows} is 1.</li>
 *   <li>reset first: the rotation read the row before the revocation
 *       committed. Only the guarded {@code markReplaced} refuses it; the old
 *       whole-row {@code save()} wrote {@code revoked_at = NULL} back.</li>
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class PinResetSignOutIntegrationTest {

    private static final String TEST_MSISDN = "+254712345678";
    private static final String DEVICE = "test-device-1";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PinHasher pinHasher;
    @Autowired LoginService loginService;
    @Autowired PinService pinService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired VerificationTokenIssuer verificationTokenIssuer;
    @Autowired PlatformTransactionManager transactionManager;

    MockMvc mockMvc;
    UUID customerId;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, consumed_verification_token, customer CASCADE");
        Instant now = Instant.now();
        customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                customerId, "KE", TEST_MSISDN, pinHasher.hash("1234"), KycTier.BASIC.dbValue(),
                "FINERACT", "EXT-SIGNOUT", CustomerStatus.ACTIVE.dbValue(),
                Timestamp.from(now), Timestamp.from(now));
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private String login(String device) {
        return loginService.login("0712345678", "1234", device).refreshToken();
    }

    private String resetToken() {
        return verificationTokenIssuer.issue(TEST_MSISDN, OtpPurpose.PIN_RESET).token();
    }

    private int liveRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ? AND revoked_at IS NULL",
                Integer.class, customerId);
    }

    @Test
    void aResetEndsEverySessionOnEveryDeviceAndAuditsIt() throws Exception {
        String phone = login(DEVICE);
        String tablet = login("test-device-2");

        mockMvc.perform(post("/auth/pin/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"9999"}
                                """.formatted(resetToken())))
                .andExpect(status().isNoContent());

        for (String[] session : new String[][]{{phone, DEVICE}, {tablet, "test-device-2"}}) {
            mockMvc.perform(post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s","deviceHash":"%s"}
                                    """.formatted(session[0], session[1])))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));
        }
        assertThat(liveRows()).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT revocation_reason FROM refresh_token WHERE customer_id = ?",
                String.class, customerId)).containsExactly("pin_reset");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata->>'refreshTokensRevoked' FROM audit_event WHERE action = 'pin_reset'",
                String.class)).isEqualTo("2");

        // The new PIN signs in as normal afterwards.
        assertThat(loginService.login("0712345678", "9999", DEVICE).refreshToken()).isNotBlank();
    }

    @Test
    void aRotationInFlightWhenTheResetStartsIsRevokedWithEverythingElse() throws Exception {
        String refresh = login(DEVICE);
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // rotate() joins this outer transaction, so its successor stays
        // uncommitted (and its customer SHARE lock held) until release.
        Future<RefreshToken> rotation = pool.submit(() -> tx.execute(status -> {
            RefreshToken next = refreshTokenService.rotate(refresh, DEVICE);
            rotated.countDown();
            await(release);
            return next;
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();

        String token = resetToken();
        Future<?> reset = pool.submit(() -> pinService.resetPin(token, "9999"));
        Thread.sleep(500);
        assertThat(reset.isDone()).as("the reset waits for the in-flight rotation").isFalse();

        release.countDown();
        RefreshToken successor = rotation.get(10, TimeUnit.SECONDS);
        reset.get(10, TimeUnit.SECONDS);

        assertThat(liveRows()).as("the successor minted mid-reset must not survive it").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM refresh_token WHERE jti = ?",
                String.class, successor.getJti())).isEqualTo("pin_reset");
    }

    @Test
    void aRotationThatReadTheRowBeforeTheResetCommittedIsRefused() throws Exception {
        String refresh = login(DEVICE);
        String token = resetToken();
        CountDownLatch resetDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // The reset's writes are made but not committed: its customer lock is held.
        Future<?> reset = pool.submit(() -> tx.executeWithoutResult(status -> {
            pinService.resetPin(token, "9999");
            resetDone.countDown();
            await(release);
        }));
        assertThat(resetDone.await(10, TimeUnit.SECONDS)).isTrue();

        Future<RefreshToken> rotation = pool.submit(() -> refreshTokenService.rotate(refresh, DEVICE));
        Thread.sleep(500);
        assertThat(rotation.isDone()).as("rotation waits for the uncommitted reset").isFalse();

        release.countDown();
        reset.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> rotation.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RefreshTokenInvalidException.class);
        assertThat(liveRows()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ?", Integer.class, customerId))
                .as("no successor was minted").isEqualTo(1);
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
