package zw.co.innbucks.middleware.otp.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@Import(PostgresTestContainer.class)
class OtpControllerTest {

    /**
     * Force the OTP code to a deterministic value so the verify test can
     * supply it. Avoids depending on the dev profile's fixed-code default.
     */
    @DynamicPropertySource
    static void otpProps(DynamicPropertyRegistry registry) {
        registry.add("innbucks.otp.fixed-code", () -> "654321");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE otp_challenge, audit_event CASCADE");
    }

    @Test
    void requestReturns204AndPersistsActiveChallenge() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM otp_challenge WHERE msisdn = '+254712345678' "
                        + "AND purpose = 'PIN_SETUP' AND consumed_at IS NULL AND superseded_at IS NULL",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void requestReturns204EvenForMalformedMsisdn_antiEnumeration() throws Exception {
        // Non-Kenyan MSISDN — KenyaMsisdnNormalizer would throw InvalidMsisdnException.
        // The endpoint MUST still return 204 to match the always-204 contract that
        // protects against MSISDN-shape enumeration. Previously this surfaced as
        // 401 invalid_credentials, distinguishing valid-shape from invalid-shape inputs.
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"+255712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM otp_challenge", Long.class);
        assertThat(count).isZero();
    }

    @Test
    void newRequestSupersedesPriorActiveChallenge() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/otp/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                    """))
                    .andExpect(status().isNoContent());
        }

        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM otp_challenge WHERE msisdn = '+254712345678' "
                        + "AND purpose = 'PIN_SETUP' AND consumed_at IS NULL AND superseded_at IS NULL",
                Long.class);
        Long supersededCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM otp_challenge WHERE superseded_at IS NOT NULL",
                Long.class);
        assertThat(activeCount).isEqualTo(1L);
        assertThat(supersededCount).isEqualTo(2L);
    }

    @Test
    void verifyAcceptsCorrectCodeAndIssuesVerificationToken() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"654321"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.purpose").value("PIN_SETUP"));

        // After successful verify, the challenge must be consumed (not reusable).
        Long consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM otp_challenge WHERE consumed_at IS NOT NULL",
                Long.class);
        assertThat(consumed).isEqualTo(1L);
    }

    @Test
    void verifyRejectsWrongCodeAsOtpInvalid() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"000000"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("otp_invalid"));
    }

    @Test
    void verifyWithoutAnyActiveChallengeReturnsOtpNotFound() throws Exception {
        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"654321"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("otp_not_found"));
    }

    @Test
    void verifyAfterExpiryReturnsOtpExpired() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        // Force the active challenge to be expired without waiting 5 minutes.
        Instant past = Instant.now().minusSeconds(60);
        jdbcTemplate.update("UPDATE otp_challenge SET expires_at = ? WHERE consumed_at IS NULL",
                Timestamp.from(past));

        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"654321"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("otp_expired"));
    }

    @Test
    void verifyExhaustsAttemptsAndInvalidatesChallenge() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP"}
                                """))
                .andExpect(status().isNoContent());

        String wrongBody = """
                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"000000"}
                """;
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/auth/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongBody))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("otp_invalid"));
        }
        // 6th attempt: even the right code is rejected because attempts hit the cap.
        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345678","purpose":"PIN_SETUP","code":"654321"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("otp_attempts_exhausted"));
    }
}
