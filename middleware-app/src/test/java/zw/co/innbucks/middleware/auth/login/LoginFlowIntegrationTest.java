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
}
