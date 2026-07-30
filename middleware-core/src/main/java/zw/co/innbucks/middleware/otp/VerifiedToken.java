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
        Instant expiresAt,

        /**
         * STEP_UP tokens only: the transaction fingerprint this token approves.
         * Null on PIN_SETUP / PIN_RESET tokens (and on any STEP_UP token minted
         * without one — which the movement check then rejects).
         */
        String txnFp
) {
}
