package zw.co.innbucks.middleware.auth.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.auth.config.JwtConfig;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the staged HS256 → RS256 migration contract: RS256 minting carries the
 * {@code kid}, the dual-verify decoder accepts BOTH algorithms selected by the
 * token's own {@code alg} header, RS256 without a public key fails closed, and
 * signing misconfiguration fails at construction — never per-request.
 */
class JwtIssuerRs256Test {

    private static final String HS_KEY = "test-jwt-signing-key-that-is-at-least-32-bytes-long!";
    private static final String VERIFY_KEY = "test-verification-signing-key-at-least-32-bytes-long";

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final String PRIVATE_PEM = pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
    private static final String PUBLIC_PEM = pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded());

    private final CountryProperties country = new CountryProperties(Country.KE);
    private final Clock clock = Clock.systemUTC();

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private AuthProperties props(String alg, String privateKey, String publicKey, String keyId) {
        return new AuthProperties(
                HS_KEY, VERIFY_KEY, "innbucks-mobile",
                Duration.ofMinutes(10), Duration.ofDays(30),
                new AuthProperties.BruteForce(5, Duration.ofMinutes(15), Duration.ofSeconds(1)),
                alg, privateKey, publicKey, keyId);
    }

    private String mint(AuthProperties properties) {
        return new JwtIssuer(properties, country, clock).issue(new JwtIssuer.IssueRequest(
                "550e8400-e29b-41d4-a716-446655440000", Country.KE, "basic",
                Set.of("customer:read", "customer:write"), "device-hash", null));
    }

    @Test
    void rs256MintCarriesTheAlgAndKidAndVerifiesAgainstThePublicKey() throws Exception {
        String token = mint(props("RS256", PRIVATE_PEM, null, "cell-ke-2026"));

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("cell-ke-2026");
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) KEY_PAIR.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("country")).isEqualTo("KE");
    }

    @Test
    void dualVerifyDecoderAcceptsBothAlgorithmsByTheTokensOwnHeader() {
        AuthProperties dualProps = props("RS256", PRIVATE_PEM, PUBLIC_PEM, null);
        JwtDecoder decoder = new JwtConfig().jwtDecoder(dualProps, country);

        Jwt rs256 = decoder.decode(mint(dualProps));
        assertThat(rs256.getSubject()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");

        // An HS256 token minted with the shared secret (the in-flight-token
        // population during the migration window) must still verify.
        Jwt hs256 = decoder.decode(mint(props(null, null, null, null)));
        assertThat(hs256.getSubject()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void hs256RemainsTheDefaultWhenNothingIsConfigured() throws Exception {
        String token = mint(props(null, null, null, null));
        assertThat(SignedJWT.parse(token).getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
    }

    @Test
    void withoutAPublicKeyAnRs256TokenIsRejectedFailClosed() {
        JwtDecoder decoder = new JwtConfig().jwtDecoder(props(null, null, null, null), country);
        String rs256Token = mint(props("RS256", PRIVATE_PEM, null, null));

        assertThatThrownBy(() -> decoder.decode(rs256Token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rs256WithoutAPrivateKeyFailsAtConstructionNotPerRequest() {
        assertThatThrownBy(() -> new JwtIssuer(props("RS256", null, null, null), country, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key");
    }

    @Test
    void unknownAlgorithmFailsAtConstruction() {
        assertThatThrownBy(() -> new JwtIssuer(props("ES256", null, null, null), country, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ES256");
    }

    @Test
    void garbagePemFailsLoudlyWithTheExpectedType() {
        assertThatThrownBy(() -> new JwtIssuer(
                props("RS256", "-----BEGIN PRIVATE KEY-----\nnot-base64!\n-----END PRIVATE KEY-----", null, null),
                country, clock))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtConfig().jwtDecoder(
                props(null, null, "no pem block here", null), country))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BEGIN PUBLIC KEY");
    }

    @Test
    void escapedNewlinePemFromEnvVarPlumbingParses() {
        // compose/k8s single-line env values arrive with literal \n escapes.
        String singleLine = PRIVATE_PEM.replace("\n", "\\n");
        String token = mint(props("RS256", singleLine, null, null));
        assertThat(token).isNotBlank();
    }
}
