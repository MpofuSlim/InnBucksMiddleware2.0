package zw.co.innbucks.middleware.auth.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PostgresTestContainer.class)
class AuthControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JwtIssuer issuer;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsPrincipalForValidToken() throws Exception {
        // Token must carry customer:read to clear the @PreAuthorize gate on /auth/me;
        // accounts:read is carried alongside to keep asserting extra scopes pass through.
        Set<String> scopes = new HashSet<>(CustomerScopes.DEFAULT);
        scopes.add("accounts:read");
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                "customer-42",
                Country.KE,
                "standard",
                scopes,
                "device-hash-xyz",
                null
        ));

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("customer-42"))
                .andExpect(jsonPath("$.country").value("KE"))
                .andExpect(jsonPath("$.kycTier").value("standard"))
                .andExpect(jsonPath("$.deviceHash").value("device-hash-xyz"))
                .andExpect(jsonPath("$.scopes", containsInAnyOrder(
                        "customer:read", "customer:write", "accounts:read")));
    }

    @Test
    void rejectsMalformedToken() throws Exception {
        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsValidTokenWithoutCustomerReadScope() throws Exception {
        // Authenticated but missing customer:read -> the @PreAuthorize gate denies with 403.
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                "customer-42",
                Country.KE,
                "standard",
                Set.of("accounts:read"),
                "device-hash-xyz",
                null
        ));

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
