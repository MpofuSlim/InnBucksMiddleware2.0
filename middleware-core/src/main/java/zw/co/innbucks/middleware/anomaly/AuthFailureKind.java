package zw.co.innbucks.middleware.anomaly;

/**
 * What kind of authentication failure is being reported to
 * {@link AuthAnomalyDetector}.
 *
 * <p>Deliberately NOT including refresh-token failures: a refresh token is a
 * 128-bit random secret, so guessing one is not a brute-force problem — and
 * replay of a real one is already handled hard (whole-family revocation on
 * replay or device mismatch). Adding it here would only add noise.
 */
public enum AuthFailureKind {

    /** Wrong PIN, unknown MSISDN, or an attempt against a locked / backed-off account. */
    LOGIN,

    /** Wrong, expired, missing or exhausted OTP code. */
    OTP_VERIFY;

    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
