package zw.co.innbucks.middleware.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * In-memory token-bucket limiter. One Bucket4j bucket per key, held in a
 * Caffeine cache that evicts idle keys and is capped in size, so the
 * otherwise-unbounded cardinality of client IPs + MSISDNs cannot grow the heap
 * without limit.
 *
 * <p>A bucket evicted while idle recreates FULL on next use. That is harmless
 * only once it would have refilled anyway, i.e. after an idle stretch at least
 * as long as its refill period. So each key is kept idle for its own window
 * or an hour, whichever is LONGER. A flat one-hour eviction silently turned
 * the KYC create budget (5 a day) into 5 per quiet hour, and would do the
 * same to any window an operator raises past an hour through the env.
 *
 * <p>Single-instance only — see {@link RateLimitProperties}.
 */
@Service
public class RateLimiterService {

    /** The shortest idle time before a key may be evicted. */
    static final Duration MIN_IDLE = Duration.ofHours(1);

    /** Keeps {@link Duration#toNanos()} in range for an absurd configured window. */
    private static final Duration MAX_IDLE = Duration.ofDays(366);

    private final RateLimitProperties properties;
    private final Cache<String, Held> buckets;

    /** A bucket and how long it may sit idle before eviction loses nothing. */
    private record Held(Bucket bucket, long idleNanos) {
    }

    @Autowired
    public RateLimiterService(RateLimitProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    RateLimiterService(RateLimitProperties properties, Ticker ticker) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Held>() {
                    @Override
                    public long expireAfterCreate(String key, Held held, long currentTime) {
                        return held.idleNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Held held, long currentTime, long currentDuration) {
                        return held.idleNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Held held, long currentTime, long currentDuration) {
                        return held.idleNanos();
                    }
                })
                .maximumSize(100_000)
                .ticker(ticker)
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
        Bucket bucket = buckets.get(key, k -> new Held(newBucket(limit), idleNanos(limit))).bucket();
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

    /**
     * Per-CUSTOMER guard for step-up approval SMS. Keyed on the authenticated
     * customer, NOT the number: the per-number OTP bucket is spendable by
     * strangers through the public PIN-reset request, and a stranger must not
     * be able to block the owner's high-value payments.
     */
    public void enforceCustomerStepUpRequest(UUID customerId) {
        enforce("customer:stepup-otp:" + customerId, properties.customerStepUpRequest());
    }

    /** Per-CUSTOMER request budget on the money-movement endpoints. */
    public void enforceCustomerPayment(UUID customerId) {
        enforce("customer:payment:" + customerId, properties.customerPayment());
    }

    /**
     * Throwing form of {@link #tryConsume}. A {@code null} limit (the key
     * missing from configuration) allows rather than failing every request
     * with a NullPointerException — the shipped application.yaml always sets
     * one.
     */
    private void enforce(String key, Limit limit) {
        if (!properties.enabled() || limit == null) {
            return;
        }
        RateLimitDecision decision = tryConsume(key, limit);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }

    private static long idleNanos(Limit limit) {
        Duration window = limit.refillPeriod();
        Duration idle = window.compareTo(MIN_IDLE) > 0 ? window : MIN_IDLE;
        return (idle.compareTo(MAX_IDLE) > 0 ? MAX_IDLE : idle).toNanos();
    }

    private static Bucket newBucket(Limit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillGreedy(limit.capacity(), limit.refillPeriod())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
