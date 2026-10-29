package zw.co.innbucks.middleware.kyc;

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
import zw.co.innbucks.middleware.kyc.crypto.KycKeyRing;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default: KYC off. The app boots with no KYC key anywhere, and a
 * signed-in caller gets the plain 404 of an unknown path — the routes do not
 * exist. Same context as the other default-config ITs (no extra properties).
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class KycSwitchedOffIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JwtIssuer issuer;

    MockMvc mvc;
    String bearer;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        bearer = "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                UUID.randomUUID().toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null));
    }

    @Test
    void offMeansAbsent() throws Exception {
        assertThat(context.getBeanNamesForType(KycKeyRing.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KycService.class)).isEmpty();

        mvc.perform(get("/me/kyc").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("not_found"));
        mvc.perform(post("/me/kyc/submissions").header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"standard\",\"nationalId\":\"12345678\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("not_found"));
        mvc.perform(post("/me/kyc/submissions/" + UUID.randomUUID() + "/files/selfie")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[]{(byte) 0xFF, (byte) 0xD8}))
                .andExpect(status().isNotFound());
    }
}
