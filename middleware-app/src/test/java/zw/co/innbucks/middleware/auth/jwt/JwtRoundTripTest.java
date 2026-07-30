package zw.co.innbucks.middleware.auth.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestContainer.class)
class JwtRoundTripTest {

    @Autowired
    JwtIssuer issuer;

    @Autowired
    JwtDecoder decoder;

    @Test
    void issuesTokenThatDecodesWithExpectedClaims() {
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                "customer-123",
                Country.KE,
                "standard",
                Set.of("accounts:read", "transfers:initiate"),
                "device-hash-xyz",
                "nid-hash-abc"
        ));

        Jwt decoded = decoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("customer-123");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("innbucks-ke");
        assertThat(decoded.getAudience()).contains("innbucks-mobile-test");
        assertThat(decoded.getClaimAsString(InnbucksClaims.COUNTRY)).isEqualTo("KE");
        assertThat(decoded.getClaimAsString(InnbucksClaims.KYC_TIER)).isEqualTo("standard");
        assertThat(decoded.getClaimAsString(InnbucksClaims.DEVICE_ID)).isEqualTo("device-hash-xyz");
        assertThat(decoded.getClaimAsString(InnbucksClaims.NATIONAL_ID_HASH)).isEqualTo("nid-hash-abc");
        assertThat(decoded.getClaimAsStringList(InnbucksClaims.SCOPES))
                .containsExactlyInAnyOrder("accounts:read", "transfers:initiate");
        assertThat(decoded.getExpiresAt()).isAfter(decoded.getIssuedAt());
    }

    @Test
    void principalIsExtractedFromDecodedJwt() {
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                "customer-9",
                Country.KE,
                "basic",
                Set.of("accounts:read"),
                null,
                null
        ));

        Jwt decoded = decoder.decode(token);
        InnbucksPrincipal principal = InnbucksPrincipal.fromJwt(decoded);

        assertThat(principal.subject()).isEqualTo("customer-9");
        assertThat(principal.country()).isEqualTo(Country.KE);
        assertThat(principal.kycTier()).isEqualTo("basic");
        assertThat(principal.scopes()).containsExactly("accounts:read");
        assertThat(principal.authTime()).isNotNull();
    }
}
