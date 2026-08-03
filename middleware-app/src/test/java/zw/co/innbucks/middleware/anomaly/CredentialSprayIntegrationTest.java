package zw.co.innbucks.middleware.anomaly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.ratelimit.AuthRateLimitFilter;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The only proof that the whole chain fires in a real context: a failed login
 * reaches the detector from inside {@code LoginService}, the detector's verdict
 * reaches {@code AuthRateLimitFilter}, and the filter turns the source away
 * before Spring Security ever sees the next request.
 *
 * <p>Thresholds are dialled down to 2/3 here; the shipped defaults are 10/30.
 * Each test uses its own source address because the detector is a singleton
 * whose block state would otherwise leak between tests — the same isolation a
 * real deployment gets for free from having many clients.
 */
@SpringBootTest(properties = {
        "innbucks.security.anomaly.enabled=true",
        "innbucks.security.anomaly.window=15m",
        "innbucks.security.anomaly.distinct-subjects-alert=2",
        "innbucks.security.anomaly.distinct-subjects-block=3",
        "innbucks.security.anomaly.block-duration=15m",
        "innbucks.security.anomaly.block-enabled=true"
})
@Import(PostgresTestContainer.class)
class CredentialSprayIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * MockMvc does not pick up application filters on its own, so the filter
     * under test is added explicitly — without this the request would never
     * reach the block and the test would pass for the wrong reason.
     */
    @Autowired
    AuthRateLimitFilter rateLimitFilter;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(rateLimitFilter)
                .apply(springSecurity())
                .build();
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");
    }

    private ResultActions attempt(String msisdn, String sourceIp) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .with(request -> {
                    request.setRemoteAddr(sourceIp);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"msisdn":"%s","pin":"9999","deviceHash":"device-under-test"}
                        """.formatted(msisdn)));
    }

    @Test
    void aSourceWorkingThroughDistinctAccountsIsBlockedFromTheAuthSurface() throws Exception {
        String attacker = "203.0.113.10";

        // Numbers that are valid for this cell but not registered — the
        // enumeration phase of a spray, which must be counted precisely
        // BECAUSE no account exists to lock.
        attempt("0712345601", attacker).andExpect(status().isUnauthorized());
        attempt("0712345602", attacker).andExpect(status().isUnauthorized());
        // Third distinct account crosses the block threshold. The request that
        // crosses it is still answered normally...
        attempt("0712345603", attacker).andExpect(status().isUnauthorized());

        // ...the next one never reaches the login logic at all.
        attempt("0712345604", attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("rate_limited"));

        Integer sprayRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'credential_spray_detected'",
                Integer.class);
        // One row for crossing the alert level, one for the block — never one
        // per attempt, which would serialise an attack on the audit chain.
        assertThat(sprayRows).isEqualTo(2);
    }

    @Test
    void oneAccountFailingRepeatedlyIsNeverTreatedAsASpray() throws Exception {
        String office = "203.0.113.11";

        // Five failures, one account: the shape of a customer who cannot
        // remember their PIN, and the reason the thresholds count accounts
        // rather than attempts. Behind a NAT this is the common case.
        for (int i = 0; i < 5; i++) {
            attempt("0712345699", office).andExpect(status().isUnauthorized());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'credential_spray_detected'",
                Integer.class)).isZero();
    }

    @Test
    void aBlockedSourceDoesNotAffectAnyoneElse() throws Exception {
        String attacker = "203.0.113.12";
        String innocent = "203.0.113.13";

        attempt("0712345611", attacker).andExpect(status().isUnauthorized());
        attempt("0712345612", attacker).andExpect(status().isUnauthorized());
        attempt("0712345613", attacker).andExpect(status().isUnauthorized());
        attempt("0712345614", attacker).andExpect(status().isTooManyRequests());

        // Same instant, different source: unaffected.
        attempt("0712345615", innocent).andExpect(status().isUnauthorized());
    }
}
