package zw.co.innbucks.middleware.otp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.auth.config.AuthProperties;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;

/**
 * Verifies the JWT minted by {@link VerificationTokenIssuer}, against the
 * DEDICATED verification signing key (never the access-token key — see
 * {@link zw.co.innbucks.middleware.auth.config.AuthProperties}). We DON'T
 * reuse the access-token {@code JwtDecoder} because that one validates
 * audience = {@code innbucks-mobile} and would reject these — exactly the
 * property we want, so this verifier is isolated to the PIN-setup /
 * PIN-reset flow.
 */
@Component
@RequiredArgsConstructor
public class VerificationTokenVerifier {

    private final AuthProperties authProperties;
    private final CountryProperties countryProperties;
    private final Clock clock;

    public VerifiedToken verify(String token, OtpPurpose expectedPurpose) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException ex) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.MALFORMED, ex.getMessage());
        }

        try {
            boolean signatureOk = jwt.verify(
                    new MACVerifier(authProperties.verificationSigningKey().getBytes(StandardCharsets.UTF_8)));
            if (!signatureOk) {
                throw new VerificationTokenInvalidException(
                        VerificationTokenInvalidException.Reason.BAD_SIGNATURE, "HMAC signature did not verify");
            }
        } catch (JOSEException ex) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.BAD_SIGNATURE, ex.getMessage());
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException ex) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.MALFORMED, ex.getMessage());
        }

        if (claims.getAudience() == null
                || !claims.getAudience().contains(VerificationTokenIssuer.AUDIENCE)) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.WRONG_AUDIENCE,
                    "expected aud=" + VerificationTokenIssuer.AUDIENCE);
        }

        // Issuer is pinned per-deployment, computed exactly as VerificationTokenIssuer
        // builds it. A token minted by a sibling-country deployment (same signing key
        // would still verify the signature) is rejected here.
        String expectedIssuer = "innbucks-" + countryProperties.country().name().toLowerCase();
        if (claims.getIssuer() == null || !claims.getIssuer().equals(expectedIssuer)) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.WRONG_ISSUER,
                    "expected iss=" + expectedIssuer);
        }

        Date exp = claims.getExpirationTime();
        if (exp == null || exp.toInstant().isBefore(clock.instant())) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.EXPIRED,
                    "expired at " + exp);
        }

        String purposeClaim = (String) claims.getClaim(VerificationTokenIssuer.CLAIM_PURPOSE);
        if (purposeClaim == null || !purposeClaim.equals(expectedPurpose.name())) {
            throw new VerificationTokenInvalidException(
                    VerificationTokenInvalidException.Reason.PURPOSE_MISMATCH,
                    "purpose=" + purposeClaim + " expected=" + expectedPurpose);
        }

        Object txnFpClaim = claims.getClaim(VerificationTokenIssuer.CLAIM_TXN_FP);
        return new VerifiedToken(
                claims.getSubject(),
                OtpPurpose.valueOf(purposeClaim),
                claims.getJWTID(),
                claims.getExpirationTime().toInstant(),
                txnFpClaim == null ? null : txnFpClaim.toString());
    }
}
