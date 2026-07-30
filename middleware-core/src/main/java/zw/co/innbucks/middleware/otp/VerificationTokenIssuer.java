package zw.co.innbucks.middleware.otp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Mints short-lived JWTs that prove "this MSISDN just verified an OTP for
 * purpose X". Verified later by the {@code /auth/pin/set} endpoint.
 *
 * Signed with a DEDICATED key ({@code innbucks.auth.verification-signing-key})
 * — never the access-token key. With a shared key, any party able to mint
 * access tokens could also mint PIN-reset verification tokens: an
 * account-takeover primitive. The distinct audience ({@code innbucks-otp-verify})
 * additionally stops a verification token being accepted as a Bearer access
 * token by the regular JwtDecoder, which validates {@code aud=innbucks-mobile}.
 */
public class VerificationTokenIssuer {

    public static final String AUDIENCE = "innbucks-otp-verify";
    public static final String CLAIM_PURPOSE = "purpose";
    /** Step-up only: the transaction fingerprint this token approves — nothing else. */
    public static final String CLAIM_TXN_FP = "txn_fp";

    private final JWSSigner signer;
    private final String issuer;
    private final OtpProperties otpProperties;
    private final Clock clock;

    public VerificationTokenIssuer(AuthProperties authProperties,
                                   OtpProperties otpProperties,
                                   CountryProperties countryProperties,
                                   Clock clock) {
        try {
            this.signer = new MACSigner(authProperties.verificationSigningKey().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to construct verification-token signer", ex);
        }
        this.issuer = "innbucks-" + countryProperties.country().name().toLowerCase();
        this.otpProperties = otpProperties;
        this.clock = clock;
    }

    public VerificationToken issue(String msisdn, OtpPurpose purpose) {
        return issue(msisdn, purpose, Map.of());
    }

    /**
     * Issue with extra claims — today only {@link #CLAIM_TXN_FP}, which binds
     * a STEP_UP token to exactly one transaction fingerprint so it can never
     * approve any other movement.
     */
    public VerificationToken issue(String msisdn, OtpPurpose purpose, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(otpProperties.verificationTokenTtl());

        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject(msisdn)
                .issuer(issuer)
                .audience(AUDIENCE)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim(CLAIM_PURPOSE, purpose.name());
        extraClaims.forEach(builder::claim);
        JWTClaimsSet claims = builder.build();

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(signer);
            return new VerificationToken(jwt.serialize(), otpProperties.verificationTokenTtl());
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign verification token", ex);
        }
    }
}
