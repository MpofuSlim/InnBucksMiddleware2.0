package zw.co.innbucks.middleware.otp;

import lombok.Getter;

/**
 * Thrown by {@link VerificationTokenVerifier#verify} when the supplied
 * verification token is missing, malformed, expired, signed with the wrong
 * key, has the wrong audience/issuer, carries a purpose other than the one the
 * caller asked for, or has already been consumed (single-use replay).
 */
@Getter
public class VerificationTokenInvalidException extends RuntimeException {

    public enum Reason {
        MALFORMED,
        BAD_SIGNATURE,
        WRONG_AUDIENCE,
        WRONG_ISSUER,
        EXPIRED,
        PURPOSE_MISMATCH,
        REPLAYED,
        /** STEP_UP token presented for a different transaction than it approves. */
        TXN_MISMATCH
    }

    private final Reason reason;

    public VerificationTokenInvalidException(Reason reason, String detail) {
        super("Verification token invalid: " + reason + " — " + detail);
        this.reason = reason;
    }
}
