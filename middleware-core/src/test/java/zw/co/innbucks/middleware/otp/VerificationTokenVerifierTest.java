package zw.co.innbucks.middleware.otp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Pure-unit tests for {@link VerificationTokenVerifier}: no Spring context, no
 * Docker. Verification tokens are signed with a DEDICATED key, distinct from
 * the access-token key — a token signed with the access key must fail, and a
 * token signed with the right key but a foreign {@code iss} must fail on the
 * issuer check alone.
 */
class VerificationTokenVerifierTest {

    private static final String SIGNING_KEY = "test-signing-key-at-least-32-bytes-long-xx";
    private static final String VERIFICATION_KEY = "test-verification-key-at-least-32-bytes-yy";
    private static final String MSISDN = "+254712345678";

    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final AuthProperties authProperties = new AuthProperties(
            SIGNING_KEY,
            VERIFICATION_KEY,
            "innbucks-mobile",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            new AuthProperties.BruteForce(5, Duration.ofMinutes(15), Duration.ofSeconds(1)));

    private final OtpProperties otpProperties = new OtpProperties(
            Duration.ofMinutes(5), 5, Duration.ofMinutes(5), null);

    private final CountryProperties countryProperties = new CountryProperties(Country.KE);

    private final VerificationTokenIssuer issuer =
            new VerificationTokenIssuer(authProperties, otpProperties, countryProperties, clock);

    private final VerificationTokenVerifier verifier =
            new VerificationTokenVerifier(authProperties, countryProperties, clock);

    @Test
    void acceptsTokenMintedByMatchingIssuer() {
        String token = issuer.issue(MSISDN, OtpPurpose.PIN_SETUP).token();

        VerifiedToken verified = verifier.verify(token, OtpPurpose.PIN_SETUP);

        assertThat(verified.msisdn()).isEqualTo(MSISDN);
        assertThat(verified.purpose()).isEqualTo(OtpPurpose.PIN_SETUP);
        assertThat(verified.jti()).isNotBlank();
        // exp = now + verificationTokenTtl (5 min).
        assertThat(verified.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void rejectsTokenWithForeignIssuer() throws Exception {
        // Same signing key + audience + purpose, but iss claims a different
        // deployment. The signature verifies; only the iss check should fail.
        String token = mintWithIssuer("innbucks-zw");

        VerificationTokenInvalidException ex = catchThrowableOfType(
                VerificationTokenInvalidException.class,
                () -> verifier.verify(token, OtpPurpose.PIN_SETUP));

        assertThat(ex).isNotNull();
        assertThat(ex.getReason()).isEqualTo(VerificationTokenInvalidException.Reason.WRONG_ISSUER);
    }

    @Test
    void rejectsTokenWithNoIssuer() throws Exception {
        String token = mintWithIssuer(null);

        assertThatThrownBy(() -> verifier.verify(token, OtpPurpose.PIN_SETUP))
                .isInstanceOf(VerificationTokenInvalidException.class)
                .extracting(e -> ((VerificationTokenInvalidException) e).getReason())
                .isEqualTo(VerificationTokenInvalidException.Reason.WRONG_ISSUER);
    }

    @Test
    void rejectsTokenSignedWithAccessTokenKey() throws Exception {
        // The key-separation contract: a party holding the ACCESS-token signing
        // key must NOT be able to mint a PIN-reset verification token. Claims
        // are otherwise perfectly valid — only the signing key is wrong.
        String token = mintSignedWith(SIGNING_KEY, "innbucks-ke");

        assertThatThrownBy(() -> verifier.verify(token, OtpPurpose.PIN_SETUP))
                .isInstanceOf(VerificationTokenInvalidException.class)
                .extracting(e -> ((VerificationTokenInvalidException) e).getReason())
                .isEqualTo(VerificationTokenInvalidException.Reason.BAD_SIGNATURE);
    }

    /**
     * Mint a token the same shape {@link VerificationTokenIssuer} produces
     * (signature/audience/purpose/jti/exp all valid) but with a chosen issuer,
     * so the iss check is the only thing that can reject it.
     */
    private String mintWithIssuer(String issuerClaim) throws Exception {
        return mintSignedWith(VERIFICATION_KEY, issuerClaim);
    }

    private String mintSignedWith(String key, String issuerClaim) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(MSISDN)
                .issuer(issuerClaim)
                .audience(VerificationTokenIssuer.AUDIENCE)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim(VerificationTokenIssuer.CLAIM_PURPOSE, OtpPurpose.PIN_SETUP.name())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
