package zw.co.innbucks.middleware.ratelimit;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterServiceTest {

    private static RateLimitProperties props(boolean enabled, Limit limit) {
        return new RateLimitProperties(enabled, false, 1, limit, limit, limit, limit, limit, limit, limit, limit,
                limit, limit);
    }

    @Test
    void allowsUpToCapacityThenDenies() {
        Limit limit = new Limit(3, Duration.ofMinutes(1));
        RateLimiterService service = new RateLimiterService(props(true, limit));

        assertThat(service.tryConsume("ip:login:1.1.1.1", limit).allowed()).isTrue();
        assertThat(service.tryConsume("ip:login:1.1.1.1", limit).allowed()).isTrue();
        assertThat(service.tryConsume("ip:login:1.1.1.1", limit).allowed()).isTrue();

        RateLimitDecision denied = service.tryConsume("ip:login:1.1.1.1", limit);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThan(0L);
    }

    @Test
    void keysAreIsolated() {
        Limit limit = new Limit(1, Duration.ofMinutes(1));
        RateLimiterService service = new RateLimiterService(props(true, limit));

        assertThat(service.tryConsume("ip:login:1.1.1.1", limit).allowed()).isTrue();
        assertThat(service.tryConsume("ip:login:1.1.1.1", limit).allowed()).isFalse();
        // A different IP gets its own bucket.
        assertThat(service.tryConsume("ip:login:2.2.2.2", limit).allowed()).isTrue();
    }

    @Test
    void disabledAlwaysAllowsAndNeverDereferencesLimit() {
        RateLimiterService service = new RateLimiterService(props(false, null));
        for (int i = 0; i < 100; i++) {
            assertThat(service.tryConsume("ip:login:1.1.1.1", null).allowed()).isTrue();
        }
    }

    @Test
    void enforceMsisdnOtpRequestThrowsOnceExhausted() {
        Limit limit = new Limit(2, Duration.ofMinutes(15));
        RateLimiterService service = new RateLimiterService(props(true, limit));

        service.enforceMsisdnOtpRequest("254712000111");
        service.enforceMsisdnOtpRequest("254712000111");

        assertThatThrownBy(() -> service.enforceMsisdnOtpRequest("254712000111"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds()).isGreaterThan(0L));
    }

    @Test
    void disabledDoesNotThrowFromMsisdnGuard() {
        RateLimiterService service = new RateLimiterService(props(false, null));
        for (int i = 0; i < 50; i++) {
            service.enforceMsisdnOtpRequest("254712000111");
        }
    }

    @Test
    void thePaymentBudgetIsPerCustomer() {
        Limit limit = new Limit(2, Duration.ofMinutes(1));
        RateLimiterService service = new RateLimiterService(props(true, limit));
        UUID alice = UUID.randomUUID();

        service.enforceCustomerPayment(alice);
        service.enforceCustomerPayment(alice);
        assertThatThrownBy(() -> service.enforceCustomerPayment(alice))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds()).isPositive());
        // Another customer is untouched, and so is alice's step-up allowance.
        service.enforceCustomerPayment(UUID.randomUUID());
        service.enforceCustomerStepUpRequest(alice);
    }

    @Test
    void aMissingPerCustomerLimitAllowsInsteadOfFailingEveryRequest() {
        Limit limit = new Limit(1, Duration.ofMinutes(1));
        RateLimitProperties noPaymentLimit = new RateLimitProperties(true, false, 1,
                limit, limit, limit, limit, limit, limit, limit, limit, null, null);
        RateLimiterService service = new RateLimiterService(noPaymentLimit);
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            service.enforceCustomerPayment(alice);
            service.enforceCustomerStepUpRequest(alice);
        }
    }

    @Test
    void disabledDoesNotThrowFromThePerCustomerGuards() {
        RateLimiterService service = new RateLimiterService(props(false, null));
        UUID alice = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            service.enforceCustomerPayment(alice);
            service.enforceCustomerStepUpRequest(alice);
        }
    }
}
