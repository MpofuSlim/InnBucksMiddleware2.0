package zw.co.innbucks.middleware.otp;

import java.time.Instant;

/**
 * Result of a successful {@link VerificationTokenVerifier#verify} call. Proves
 * that this MSISDN proved phone ownership for this purpose within the
 * verification window.
 *
 * <p>{@code jti} + {@code expiresAt} carry the token's identity forward so the
 * consumer can enforce single-use: the {@code jti} is recorded on first
 * successful consume and the {@code expiresAt} bounds how long that record
 * needs to live (past it, the token's own {@code exp} claim already rejects it).
 */
public record VerifiedToken(

        String msisdn,
        OtpPurpose purpose,
        String jti,
        Instant expiresAt
) {
}
