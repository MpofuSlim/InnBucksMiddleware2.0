package zw.co.innbucks.middleware.me;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.InnbucksClaims;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The identity token through the real security chain: it reads
 * GET /me/identity and is refused everywhere else, including on a path no
 * {@code @PreAuthorize} guards (the filter rule), while the app's access
 * token is refused on /me/identity.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class IdentityTokenIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    MockMvc mvc;
    UUID customerId;
    String accessToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE idempotency_record, ledger_transaction_event, ledger_transaction, "
                + "audit_event, refresh_token, customer CASCADE");
        customerId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                                      status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', '+254712345690', '$argon2id$stub', 'basic', 'FINERACT', ?, 'active', 0, ?, ?)
                """, customerId, customerId.toString(), Timestamp.from(now), Timestamp.from(now));
        accessToken = issuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, "device-hash"));
    }

    private String identityToken() throws Exception {
        String body = mvc.perform(post("/me/identity-token").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.expiresIn").value(120))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("identityToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void theIdentityTokenCarriesOnlyIdentityReadAndLivesTwoMinutes() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(identityToken()).getJWTClaimsSet();

        assertThat(claims.getSubject()).isEqualTo(customerId.toString());
        assertThat(claims.getStringListClaim(InnbucksClaims.SCOPES)).containsExactly(CustomerScopes.IDENTITY_READ);
        assertThat(claims.getClaim(InnbucksClaims.KYC_TIER)).isNull();
        assertThat(claims.getClaim(InnbucksClaims.DEVICE_ID)).isNull();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void itReadsWhoTheCustomerIs() throws Exception {
        mvc.perform(get("/me/identity").header(HttpHeaders.AUTHORIZATION, bearer(identityToken())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.msisdn").value("+254712345690"));
    }

    @Test
    void itIsRefusedEverywhereElse() throws Exception {
        String identity = bearer(identityToken());

        mvc.perform(get("/me/profile").header(HttpHeaders.AUTHORIZATION, identity))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("insufficient_scope"));
        mvc.perform(get("/me/accounts").header(HttpHeaders.AUTHORIZATION, identity))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, identity))
                .andExpect(status().isForbidden());
        mvc.perform(post("/transactions/withdraw").header(HttpHeaders.AUTHORIZATION, identity)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s:wallet","amountMinor":1000,"currency":"USD"}
                                """.formatted(customerId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("insufficient_scope"));
        // No chaining: an identity token cannot mint another.
        mvc.perform(post("/me/identity-token").header(HttpHeaders.AUTHORIZATION, identity))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Integer.class))
                .isZero();
    }

    /**
     * The filter rule, not the annotations: a path no handler guards (here one
     * that does not exist) is a 403 for an identity token, where the app's
     * access token reaches the dispatcher and gets the 404. An endpoint that
     * ever forgets its @PreAuthorize stays closed to identity tokens.
     */
    @Test
    void anUnguardedPathIsClosedToItAtTheFilter() throws Exception {
        mvc.perform(get("/me/not-an-endpoint").header(HttpHeaders.AUTHORIZATION, bearer(identityToken())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
                .andExpect(jsonPath("$.errorCode").value("insufficient_scope"));
        mvc.perform(get("/me/not-an-endpoint").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theAppsAccessTokenIsRefusedOnTheIdentityEndpoint() throws Exception {
        mvc.perform(get("/me/identity").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("insufficient_scope"));
    }

    @Test
    void noCustomerBehindTheTokenIsNotFound() throws Exception {
        String orphan = issuer.issue(new JwtIssuer.IssueRequest(UUID.randomUUID().toString(), Country.KE, null,
                java.util.Set.of(CustomerScopes.IDENTITY_READ), null), Duration.ofMinutes(2));
        mvc.perform(get("/me/identity").header(HttpHeaders.AUTHORIZATION, bearer(orphan)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("customer_not_registered"));
    }

    @Test
    void anUnauthenticatedCallIsStillA401() throws Exception {
        mvc.perform(get("/me/identity")).andExpect(status().isUnauthorized());
        mvc.perform(post("/me/identity-token")).andExpect(status().isUnauthorized());
    }
}
