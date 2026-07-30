package zw.co.innbucks.middleware.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.auth.jwt.PemKeys;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtIssuer jwtIssuer(AuthProperties authProperties,
                               CountryProperties countryProperties,
                               Clock authClock) {
        return new JwtIssuer(authProperties, countryProperties, authClock);
    }

    /**
     * Dual-verify decoder for the staged HS256 → RS256 migration (see
     * {@link AuthProperties#signingAlgorithm()}): the verification key is
     * selected by the TOKEN'S OWN {@code alg} header — RS* tokens verify
     * against the optional {@code innbucks.auth.public-key}, everything else
     * against the HS256 secret. With no public key configured, RS256 tokens
     * are rejected (fail closed); either way issuer/audience/expiry are then
     * validated exactly as before.
     */
    @Bean
    public JwtDecoder jwtDecoder(AuthProperties authProperties, CountryProperties countryProperties) {
        SecretKeySpec hmacKey = new SecretKeySpec(
                authProperties.signingKey().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        // Parsed once at boot — a malformed configured key stops startup, not requests.
        RSAPublicKey rsaPublicKey =
                authProperties.publicKey() == null || authProperties.publicKey().isBlank()
                        ? null
                        : PemKeys.parsePublicKey(authProperties.publicKey());

        JWSKeySelector<SecurityContext> keySelector = (header, context) -> {
            if (JWSAlgorithm.Family.RSA.contains(header.getAlgorithm())) {
                if (rsaPublicKey == null) {
                    throw new KeySourceException(
                            "RS256 token presented but innbucks.auth.public-key (env JWT_PUBLIC_KEY) is not configured");
                }
                if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
                    throw new KeySourceException("Only RS256 is accepted from the RSA family");
                }
                return List.<Key>of(rsaPublicKey);
            }
            if (JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
                return List.<Key>of(hmacKey);
            }
            throw new KeySourceException(
                    "Unsupported JWS algorithm " + header.getAlgorithm() + " — expected HS256 or RS256");
        };

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        // Claims validation belongs to the Spring validators below (same set
        // as before the RS256 migration) — disable Nimbus's own pass.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);

        String expectedIssuer = "innbucks-" + countryProperties.country().name().toLowerCase();
        String expectedAudience = authProperties.audience();

        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(expectedAudience));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(expectedIssuer),
                audienceValidator
        ));

        return decoder;
    }
}
