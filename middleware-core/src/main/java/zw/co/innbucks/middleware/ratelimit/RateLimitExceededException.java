package zw.co.innbucks.middleware.ratelimit;

/**
 * Thrown when a per-key rate limit is exceeded from within the service layer
 * (today: the per-MSISDN OTP-request cap). Mapped to HTTP 429 with a
 * {@code Retry-After} header by {@code AuthExceptionHandler}. The edge per-IP
 * limit in {@link AuthRateLimitFilter} writes its own 429 directly and does not
 * use this exception.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Rate limit exceeded; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
