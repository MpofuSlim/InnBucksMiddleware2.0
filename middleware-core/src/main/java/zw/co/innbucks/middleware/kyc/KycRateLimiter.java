package zw.co.innbucks.middleware.kyc;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.ratelimit.RateLimitDecision;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;

import java.util.UUID;

/**
 * Per-customer AND per-address budgets on the three KYC writes, answered with
 * the house 429 {@code rate_limited} + Retry-After ({@link RateLimitExceededException}).
 * Checked FIRST in each write, before anything is read or decoded: an upload
 * refused here never costs a decode slot.
 *
 * <p>Both halves are spent on every call, customer first. The customer budget
 * is the one that matters (an authenticated caller cannot rotate it); the
 * address budget bounds a botnet of stolen tokens only as well as
 * {@code ClientIpResolver} resolves addresses.
 */
@Component
@ConditionalOnKycEnabled
public class KycRateLimiter {

    private final RateLimiterService limiter;
    private final KycProperties.RateLimits limits;

    public KycRateLimiter(RateLimiterService limiter, KycProperties properties) {
        this.limiter = limiter;
        this.limits = properties.rateLimit();
    }

    public void create(UUID customerId, String clientIp) {
        spend("create", limits.create(), customerId, clientIp);
    }

    public void upload(UUID customerId, String clientIp) {
        spend("upload", limits.upload(), customerId, clientIp);
    }

    public void submit(UUID customerId, String clientIp) {
        spend("submit", limits.submit(), customerId, clientIp);
    }

    private void spend(String action, KycProperties.Pair pair, UUID customerId, String clientIp) {
        refuseIfDenied(limiter.tryConsume("kyc:" + action + ":customer:" + customerId, pair.perCustomer()));
        if (clientIp != null && !clientIp.isBlank()) {
            refuseIfDenied(limiter.tryConsume("kyc:" + action + ":ip:" + clientIp, pair.perIp()));
        }
    }

    private static void refuseIfDenied(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
