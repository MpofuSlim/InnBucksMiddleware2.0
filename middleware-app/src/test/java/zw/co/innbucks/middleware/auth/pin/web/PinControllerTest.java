package zw.co.innbucks.middleware.auth.pin.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.otp.OtpPurpose;
import zw.co.innbucks.middleware.otp.VerificationToken;
import zw.co.innbucks.middleware.otp.VerificationTokenIssuer;
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
class PinControllerTest {

    private static final String TEST_MSISDN = "+254712345678";

    @DynamicPropertySource
    static void otpProps(DynamicPropertyRegistry registry) {
        registry.add("innbucks.otp.fixed-code", () -> "123456");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired VerificationTokenIssuer verificationTokenIssuer;

    final ObjectMapper objectMapper = new ObjectMapper();
    MockMvc mockMvc;
    UUID customerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, otp_challenge, customer CASCADE");

        Instant now = Instant.now();
        customerId = UUID.randomUUID();
        // Customer that just registered: PENDING_VERIFICATION, no PIN yet.
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?)
                """,
                customerId, "KE", TEST_MSISDN, KycTier.BASIC.dbValue(), "FINERACT", "EXT-PIN-TEST",
                CustomerStatus.PENDING_VERIFICATION.dbValue(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private VerificationToken mintVerificationToken(OtpPurpose purpose) {
        return verificationTokenIssuer.issue(TEST_MSISDN, purpose);
    }

    @Test
    void fullFlowRegisterToLogin() throws Exception {
        // 1. Request OTP
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        // 2. Verify OTP
        MvcResult verifyResult = mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode verifyJson = objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        String verificationToken = verifyJson.get("verificationToken").asText();

        // 3. Set PIN
        mockMvc.perform(post("/auth/pin/set")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"1234"}
                                """.formatted(verificationToken)))
                .andExpect(status().isNoContent());

        // 4. Customer is now ACTIVE with a pin_hash.
        var row = jdbcTemplate.queryForMap(
                "SELECT status, pin_hash FROM customer WHERE id = ?", customerId);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat((String) row.get("pin_hash")).startsWith("$argon2id$");

        // 5. Login now succeeds.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","pin":"1234","deviceHash":"test-device-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void setPinWithoutTokenReturns400Validation() throws Exception {
        mockMvc.perform(post("/auth/pin/set")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"","pin":"1234"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPinWithMalformedTokenReturns401VerificationTokenInvalid() throws Exception {
        mockMvc.perform(post("/auth/pin/set")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"not-a-jwt","pin":"1234"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("verification_token_invalid"));
    }

    @Test
    void setPinWithResetPurposeTokenReturns401PurposeMismatch() throws Exception {
        // Mint a PIN_RESET token but try to call /auth/pin/set (which expects PIN_SETUP).
        String token = mintVerificationToken(OtpPurpose.PIN_RESET).token();

        mockMvc.perform(post("/auth/pin/set")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"1234"}
                                """.formatted(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("verification_token_purpose_mismatch"));
    }

    @Test
    void setPinOnAlreadyActiveCustomerReturns403NotAllowed() throws Exception {
        // Flip the customer to ACTIVE first, then attempt PIN_SETUP — should be rejected.
        jdbcTemplate.update(
                "UPDATE customer SET status = ?, pin_hash = '$argon2id$placeholder' WHERE id = ?",
                CustomerStatus.ACTIVE.dbValue(), customerId);

        String token = mintVerificationToken(OtpPurpose.PIN_SETUP).token();

        mockMvc.perform(post("/auth/pin/set")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"1234"}
                                """.formatted(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("pin_set_not_allowed"));
    }

    @Test
    void resetPinOnActiveCustomerSucceedsAndClearsFailureCounters() throws Exception {
        // Set up an ACTIVE customer with stale failure state from previous wrong-PIN attempts.
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE customer
                   SET status = ?,
                       pin_hash = '$argon2id$placeholder',
                       failed_pin_attempts = 4,
                       last_failed_pin_at = ?
                 WHERE id = ?
                """,
                CustomerStatus.ACTIVE.dbValue(), Timestamp.from(now), customerId);

        String token = mintVerificationToken(OtpPurpose.PIN_RESET).token();

        mockMvc.perform(post("/auth/pin/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"9999"}
                                """.formatted(token)))
                .andExpect(status().isNoContent());

        var row = jdbcTemplate.queryForMap(
                "SELECT status, pin_hash, failed_pin_attempts, last_failed_pin_at FROM customer WHERE id = ?",
                customerId);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat((String) row.get("pin_hash")).startsWith("$argon2id$");
        assertThat(((Number) row.get("failed_pin_attempts")).intValue()).isEqualTo(0);
        assertThat(row.get("last_failed_pin_at")).isNull();
    }

    @Test
    void resetPinOnPendingVerificationCustomerReturns403NotAllowed() throws Exception {
        // Customer is still PENDING_VERIFICATION (never set initial PIN). Trying
        // to RESET is a state-machine violation.
        String token = mintVerificationToken(OtpPurpose.PIN_RESET).token();

        mockMvc.perform(post("/auth/pin/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s","pin":"1234"}
                                """.formatted(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("pin_set_not_allowed"));
    }
}
