package zw.co.innbucks.middleware.auth.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestContainer.class)
class LoginFlowIntegrationTest {

    private static final String TEST_MSISDN = "+254712345678";
    private static final String TEST_PIN = "1234";

    @Autowired
    WebApplicationContext context;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    JdbcTemplate jdbcTemplate;

    final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;

    UUID customerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");

        Instant now = Instant.now();
        this.customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                customerId,
                "KE",
                TEST_MSISDN,
                pinHasher.hash(TEST_PIN),
                KycTier.STANDARD.dbValue(),
                "FINERACT",
                "TEST-EXT-001",
                CustomerStatus.ACTIVE.dbValue(),
                0,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Test
    void loginRefreshAndLogoutFullCycle() throws Exception {
        String loginBody = """
                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                """;

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String initialRefresh = loginJson.get("refreshToken").asText();

        String refreshBody = """
                {"refreshToken":"%s","deviceHash":"test-device-1"}
                """.formatted(initialRefresh);

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode refreshJson = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String rotatedRefresh = refreshJson.get("refreshToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(initialRefresh);

        String replayBody = """
                {"refreshToken":"%s","deviceHash":"test-device-1"}
                """.formatted(initialRefresh);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replayBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_replay_detected"));

        String logoutBody = """
                {"refreshToken":"%s"}
                """.formatted(rotatedRefresh);

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutBody))
                .andExpect(status().isNoContent());
    }

    @Test
    void wrongPinReturns401InvalidCredentials() throws Exception {
        String body = """
                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                """;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));
    }

    @Test
    void unknownMsisdnReturns401InvalidCredentialsNotEnumerable() throws Exception {
        String body = """
                {"msisdn":"0700000000","pin":"1234","deviceHash":"test-device-1"}
                """;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));
    }

    @Test
    void invalidMsisdnFormatAlsoSurfacesAsInvalidCredentials() throws Exception {
        String body = """
                {"msisdn":"+255712345678","pin":"1234","deviceHash":"test-device-1"}
                """;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));
    }

    @Test
    void pendingVerificationCustomerWithoutPinReturns403PinNotSet() throws Exception {
        // Replace the ACTIVE-and-PIN-set test customer with one that just
        // registered: PENDING_VERIFICATION, pin_hash NULL. Login must NOT
        // surface as invalid_credentials — the mobile app needs a distinct
        // errorCode so it can route to the OTP+PIN-setup flow.
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");
        java.time.Instant now = java.time.Instant.now();
        UUID newId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                """,
                newId, "KE", TEST_MSISDN, KycTier.BASIC.dbValue(), "FINERACT", "TEST-EXT-PV",
                CustomerStatus.PENDING_VERIFICATION.dbValue(), 0,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        String body = """
                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                """;

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("pin_not_set"));
    }

    @Test
    void refreshFromDifferentDeviceRevokesTheWholeFamily() throws Exception {
        // Device binding (auth slice 4): the refresh token only rotates on the
        // device it was issued to. A different deviceHash is treated as theft —
        // generic refresh_invalid 401 (no oracle for the attacker) AND the
        // family is revoked, so the original device's token is dead too.
        String loginBody = """
                {"msisdn":"0712345678","pin":"1234","deviceHash":"device-A"}
                """;
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        String refresh = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("refreshToken").asText();

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s","deviceHash":"device-B"}
                                """.formatted(refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));

        // Family revoked: the ORIGINAL device can't rotate the token either.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s","deviceHash":"device-A"}
                                """.formatted(refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));

        Integer mismatchAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'refresh_device_mismatch'", Integer.class);
        assertThat(mismatchAudits).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Brute-force counters. These exist because the increment is written and
    // then thrown over, so the lockout and the backoff built on it must
    // survive InvalidCredentialsException. That now holds STRUCTURALLY — the
    // increment is its own autocommit statement in CustomerLockoutStore and
    // has committed before the exception is constructed — where it used to
    // depend on a noRollbackFor annotation on LoginService.login. Asserting
    // the RESPONSE alone cannot see a lost increment; every assertion below
    // reads the row back.
    // ---------------------------------------------------------------------

    @Test
    void aWrongPinIsCountedAgainstTheAccount() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));

        assertThat(failedAttempts()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_failed_pin_at IS NOT NULL FROM customer WHERE id = ?", Boolean.class, customerId))
                .isTrue();
    }

    @Test
    void theAttemptAtTheCapLocksTheAccount() throws Exception {
        // Seeded one short of the cap, with the last failure far enough back
        // that the exponential backoff has elapsed — this test is about the
        // lock, not the backoff, and rapid-fire attempts would hit 429 first.
        jdbcTemplate.update("UPDATE customer SET failed_pin_attempts = 6, last_failed_pin_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));

        assertThat(failedAttempts()).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM customer WHERE id = ?", String.class, customerId))
                .isEqualTo(CustomerStatus.LOCKED.dbValue());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked_until IS NOT NULL FROM customer WHERE id = ?", Boolean.class, customerId))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'account_locked'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aLockedAccountIsRefusedEvenWithTheCorrectPin() throws Exception {
        jdbcTemplate.update("UPDATE customer SET status = ?, locked_until = ?, failed_pin_attempts = 7 WHERE id = ?",
                CustomerStatus.LOCKED.dbValue(), Timestamp.from(Instant.now().plusSeconds(3600)), customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.errorCode").value("account_locked"));
    }

    @Test
    void aSuccessfulLoginClearsTheCounters() throws Exception {
        jdbcTemplate.update("UPDATE customer SET failed_pin_attempts = 3, last_failed_pin_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isOk());

        assertThat(failedAttempts()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_failed_pin_at IS NULL FROM customer WHERE id = ?", Boolean.class, customerId))
                .isTrue();
    }

    /**
     * The audit action must come from the statement's RETURNING value, not
     * from the in-memory entity — which is stale the moment the atomic UPDATE
     * runs. {@code theAttemptAtTheCapLocksTheAccount} cannot see this: it
     * seeds 6 against the test profile's cap of 5, so the stale value and the
     * returned value happen to agree. Seeding cap-1 makes them disagree.
     */
    @Test
    void theAttemptThatReachesTheCapIsAuditedAsAccountLocked() throws Exception {
        jdbcTemplate.update("UPDATE customer SET failed_pin_attempts = 4, last_failed_pin_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("invalid_credentials"));

        assertThat(failedAttempts()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM customer WHERE id = ?", String.class, customerId))
                .isEqualTo(CustomerStatus.LOCKED.dbValue());
        // Reading the stale 4 would give 4 >= 5 == false: a login_failure row
        // beside a locked row.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'account_locked'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'login_failure'", Integer.class))
                .isZero();
    }

    /**
     * Monotonicity across calls — presently unpinned, because every other
     * counter test issues exactly ONE wrong-PIN request. Catches a botched
     * {@code + 1} or a CASE that accidentally clamps.
     */
    @Test
    void theCounterClimbsAcrossSequentialAttempts() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isUnauthorized());
        assertThat(failedAttempts()).isEqualTo(1);

        // Neutralise the backoff gate by NULLing its input rather than by
        // hoping the wall clock cooperates: computeBackoffSeconds returns 0
        // immediately when last_failed_pin_at is null.
        jdbcTemplate.update("UPDATE customer SET last_failed_pin_at = NULL WHERE id = ?", customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isUnauthorized());
        assertThat(failedAttempts()).isEqualTo(2);
    }

    /**
     * The exponential backoff ladder, which had NO coverage anywhere. It
     * matters to this change because it is the only consumer of
     * {@code last_failed_pin_at}, and nothing else pins that column's VALUE —
     * only its non-nullness. An implementer who writes {@code now()}
     * server-side instead of binding the injected Clock's instant would
     * silently kill the Clock bean and still pass the whole suite.
     */
    @Test
    void theSecondRapidAttemptIsRefusedWithBackoff() throws Exception {
        // attempts=4 -> penalty min(1 << min(4-1,6), 60) = 8s, an eight-second
        // margin against the epoch-second truncation in computeBackoffSeconds.
        jdbcTemplate.update("UPDATE customer SET failed_pin_attempts = 4, last_failed_pin_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"9999","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("backoff_active"));

        // A throttled attempt does not extend the counter — today's behaviour,
        // now pinned.
        assertThat(failedAttempts()).isEqualTo(4);
    }

    /**
     * The one observable change in this slice: after a successful login the row
     * is fully consistent. Previously the success path cleared
     * {@code locked_until} but never rewrote {@code status}, so a customer
     * whose lock had EXPIRED kept a permanent stale {@code 'locked'} — inert
     * for auth (the gate requires both halves) but echoed raw to a working
     * customer by {@code GET /me/profile}. Lock expiry had no test at all.
     */
    @Test
    void anExpiredLockLetsTheCustomerBackInAndFullyResetsTheRow() throws Exception {
        jdbcTemplate.update("""
                UPDATE customer
                   SET status = ?, locked_until = ?, failed_pin_attempts = 7, last_failed_pin_at = ?
                 WHERE id = ?
                """,
                CustomerStatus.LOCKED.dbValue(),
                Timestamp.from(Instant.now().minusSeconds(3600)),
                Timestamp.from(Instant.now().minusSeconds(600)),
                customerId);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isOk());

        assertThat(failedAttempts()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_failed_pin_at IS NULL AND locked_until IS NULL FROM customer WHERE id = ?",
                Boolean.class, customerId))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM customer WHERE id = ?", String.class, customerId))
                .isEqualTo(CustomerStatus.ACTIVE.dbValue());
    }

    private Integer failedAttempts() {
        return jdbcTemplate.queryForObject(
                "SELECT failed_pin_attempts FROM customer WHERE id = ?", Integer.class, customerId);
    }
}
