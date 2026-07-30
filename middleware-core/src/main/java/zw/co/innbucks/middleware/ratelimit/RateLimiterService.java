package zw.co.innbucks.middleware.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * In-memory token-bucket limiter. One Bucket4j bucket per key, held in a
 * Caffeine cache that evicts keys idle for an hour and is capped in size, so
 * the otherwise-unbounded cardinality of client IPs + MSISDNs cannot grow the
 * heap without limit. A bucket evicted after an hour of inactivity simply
 * recreates full on next use — harmless, since every configured window is far
 * shorter than the idle-eviction window.
 *
 * <p>Single-instance only — see {@link RateLimitProperties}.
 */
@Service
public class RateLimiterService {

    private final RateLimitProperties properties;
    private final Cache<String, Bucket> buckets;

    public RateLimiterService(RateLimitProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(1))
                .maximumSize(100_000)
                .build();
    }

    /**
     * Consume one token for {@code key} against {@code limit}. When the feature
     * is disabled this is a no-op that always allows, so callers may pass a
     * {@code null} limit (e.g. the test profile leaves limits unset).
     */
    public RateLimitDecision tryConsume(String key, Limit limit) {
        if (!properties.enabled()) {
            return RateLimitDecision.allow();
        }
        Bucket bucket = buckets.get(key, k -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.allow();
        }
        long retryAfter = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        return RateLimitDecision.deny(retryAfter);
    }

    /**
     * Per-MSISDN guard for OTP requests (SMS-flood control). Throws so the
     * caller aborts before any DB write or SMS dispatch. Key on the already
     * normalized MSISDN so {@code 07..}, {@code +254..} and {@code 254..}
     * variants of the same number share one bucket.
     */
    public void enforceMsisdnOtpRequest(String normalizedMsisdn) {
        RateLimitDecision decision = tryConsume("msisdn:otp-request:" + normalizedMsisdn,
                properties.msisdnOtpRequest());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }

    private static Bucket newBucket(Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.capacity(), limit.refillPeriod())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
