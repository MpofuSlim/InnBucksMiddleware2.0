package zw.co.innbucks.middleware.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Token-bucket limits for the public, unauthenticated auth surface.
 *
 * <p>Per-IP limits are enforced at the edge by {@link AuthRateLimitFilter}; the
 * per-MSISDN OTP-request limit is enforced inside {@code OtpService} (SMS-flood
 * control). Buckets live in-memory (Caffeine), so the limits are <b>per
 * instance</b> — correct for the single-container-per-country deployment. If
 * this service is ever run with more than one replica, move to a shared store
 * (a Bucket4j Postgres/Redis {@code ProxyManager}) or the effective limit
 * multiplies by the replica count.
 */
@ConfigurationProperties(prefix = "innbucks.rate-limit")
public record RateLimitProperties(

        /** Master switch. Disable only for local debugging; keep on in staging/prod. */
        boolean enabled,

        /**
         * When true, the client IP is taken from the first {@code X-Forwarded-For}
         * hop instead of the socket peer. Enable ONLY behind a trusted proxy/LB
         * that sets the header — otherwise it is client-spoofable and a caller can
         * rotate it to dodge the per-IP limit.
         */
        boolean trustForwardedFor,

        Limit ipLogin,
        Limit ipRefresh,
        Limit ipOtpRequest,
        Limit ipOtpVerify,
        Limit ipPin,
        Limit msisdnOtpRequest
) {

    /** A bucket of {@code capacity} tokens, refilled greedily over {@code refillPeriod}. */
    public record Limit(int capacity, Duration refillPeriod) {
    }
}
