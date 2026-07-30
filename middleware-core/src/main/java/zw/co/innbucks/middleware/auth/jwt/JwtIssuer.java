package zw.co.innbucks.middleware.auth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

public class JwtIssuer {

    private final JWSSigner signer;
    private final String issuer;
    private final String audience;
    private final Duration ttl;
    private final Clock clock;

    public JwtIssuer(AuthProperties authProperties, CountryProperties countryProperties, Clock clock) {
        try {
            this.signer = new MACSigner(authProperties.signingKey().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to construct JWT signer", ex);
        }
        this.issuer = "innbucks-" + countryProperties.country().name().toLowerCase();
        this.audience = authProperties.audience();
        this.ttl = authProperties.accessTokenTtl();
        this.clock = clock;
    }

    public String issue(IssueRequest request) {
        Instant now = clock.instant();
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(request.subject())
                    .issuer(issuer)
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)))
                    .claim(InnbucksClaims.COUNTRY, request.country().name())
                    .claim(InnbucksClaims.AUTH_TIME, now.getEpochSecond());

            if (request.kycTier() != null) {
                builder.claim(InnbucksClaims.KYC_TIER, request.kycTier());
            }
            if (request.scopes() != null && !request.scopes().isEmpty()) {
                builder.claim(InnbucksClaims.SCOPES, request.scopes());
            }
            if (request.deviceHash() != null) {
                builder.claim(InnbucksClaims.DEVICE_ID, request.deviceHash());
            }
            if (request.nationalIdHash() != null) {
                builder.claim(InnbucksClaims.NATIONAL_ID_HASH, request.nationalIdHash());
            }

            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            signedJwt.sign(signer);
            return signedJwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    public record IssueRequest(
            String subject,
            Country country,
            String kycTier,
            Set<String> scopes,
            String deviceHash,
            String nationalIdHash
    ) {
    }
}
