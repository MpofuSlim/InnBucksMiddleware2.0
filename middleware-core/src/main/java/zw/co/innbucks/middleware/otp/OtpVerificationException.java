package zw.co.innbucks.middleware.otp;

/**
 * Thrown when {@link OtpService#verify} can't be satisfied. The mobile app
 * differentiates failure modes via {@link Reason} so it can render the right
 * message (e.g. "code expired — request a new one" vs. "wrong code").
 */
public class OtpVerificationException extends RuntimeException {

    public enum Reason {
        NO_ACTIVE_CHALLENGE,
        EXPIRED,
        ATTEMPTS_EXHAUSTED,
        CODE_MISMATCH
    }

    private final Reason reason;

    public OtpVerificationException(Reason reason) {
        super("OTP verification failed: " + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
