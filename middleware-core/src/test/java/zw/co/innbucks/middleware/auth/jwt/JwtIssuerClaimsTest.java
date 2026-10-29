package zw.co.innbucks.middleware.auth.jwt;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the access token's claim set EXACTLY — a new claim is a decision, not
 * a side effect. In particular there is no {@code nid_hash}: the token lives
 * on the device and is readable by anything holding it, and nothing on the
 * server ever read the national-ID hash from it.
 */
class JwtIssuerClaimsTest {

    private final JwtIssuer issuer = new JwtIssuer(
            new AuthProperties(
                    "test-jwt-signing-key-that-is-at-least-32-bytes-long!",
                    "test-verification-signing-key-at-least-32-bytes-long",
                    "innbucks-mobile", Duration.ofMinutes(10), Duration.ofDays(30),
                    new AuthProperties.BruteForce(5, Duration.ofMinutes(15), Duration.ofSeconds(1)),
                    "HS256", null, null, null),
            new CountryProperties(Country.ZW), Clock.systemUTC());

    @Test
    void theAccessTokenCarriesExactlyTheDocumentedClaims() throws Exception {
        String token = issuer.issue(new JwtIssuer.IssueRequest(
                "550e8400-e29b-41d4-a716-446655440000", Country.ZW, "basic",
                Set.of("customer:read", "customer:write"), "device-hash"));

        var claims = SignedJWT.parse(token).getJWTClaimsSet();

        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder(
                "sub", "iss", "aud", "jti", "iat", "exp",
                "country", "auth_time", "kyc_tier", "scopes", "did");
        assertThat(claims.getClaims()).doesNotContainKey("nid_hash");
        assertThat(claims.getStringClaim("kyc_tier")).isEqualTo("basic");
        assertThat(claims.getStringClaim("country")).isEqualTo("ZW");
    }
}
