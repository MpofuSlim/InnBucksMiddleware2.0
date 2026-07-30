package zw.co.innbucks.middleware.ratelimit;

/**
 * Outcome of a single {@link RateLimiterService#tryConsume} call.
 *
 * @param allowed           whether the token was granted
 * @param retryAfterSeconds when denied, seconds until the next token is available (>= 1); 0 when allowed
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0L);
    }

    public static RateLimitDecision deny(long retryAfterSeconds) {
        return new RateLimitDecision(false, retryAfterSeconds);
    }
}
