package zw.co.innbucks.middleware.fineract;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;

import java.util.function.Supplier;

/**
 * Resilience wrapper for the Fineract client — the ported, proven
 * read-retry / write-never-retry policy. One shared circuit breaker (one
 * upstream) plus a read-only retry:
 *
 * <ul>
 *   <li><b>{@link #read}</b> — idempotent lookups: retry transient failures,
 *       then the breaker.</li>
 *   <li><b>{@link #write}</b> — state-changing commands: breaker ONLY,
 *       <b>never</b> auto-retried. Retrying after an ambiguous outcome could
 *       double-move money; the propagated Idempotency-Key is the caller's
 *       dedup, not an excuse to retry. The breaker still fails writes fast
 *       when Fineract is down instead of hanging threads to the read
 *       timeout.</li>
 * </ul>
 *
 * <p>The breaker counts only Fineract-<i>down</i> signals — mapped
 * {@link CoreServerException} / {@link CoreTransientException} /
 * {@link CoreUnknownOutcomeException} (timeouts). A 4xx is our bad request,
 * not the core being unavailable, and never trips it.
 */
public class FineractResilience {

    private final boolean enabled;
    private final CircuitBreaker circuitBreaker;
    private final Retry readRetry;

    private FineractResilience(boolean enabled, CircuitBreaker circuitBreaker, Retry readRetry) {
        this.enabled = enabled;
        this.circuitBreaker = circuitBreaker;
        this.readRetry = readRetry;
    }

    public FineractResilience(FineractProperties.Resilience props) {
        this(props.enabled(), buildCircuitBreaker(props), buildReadRetry(props));
    }

    /** Pass-through for contract tests that pin the HTTP mapping without retry/breaker noise. */
    public static FineractResilience passthrough() {
        return new FineractResilience(false, null, null);
    }

    /** Idempotent read: retry transient failures, then circuit breaker. */
    public <T> T read(Supplier<T> call) {
        if (!enabled) {
            return call.get();
        }
        return CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(readRetry, call)).get();
    }

    /** State-changing write: circuit breaker only, NEVER auto-retried. */
    public <T> T write(Supplier<T> call) {
        if (!enabled) {
            return call.get();
        }
        return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
    }

    private static CircuitBreaker buildCircuitBreaker(FineractProperties.Resilience p) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumNumberOfCalls())
                .failureRateThreshold(p.failureRateThreshold())
                .waitDurationInOpenState(p.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallDurationThreshold(p.slowCallDurationThreshold())
                .recordExceptions(CoreServerException.class, CoreTransientException.class,
                        CoreUnknownOutcomeException.class)
                .build();
        return CircuitBreaker.of("fineract", config);
    }

    private static Retry buildReadRetry(FineractProperties.Resilience p) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(p.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(p.retryWaitDuration(), 2.0))
                .retryExceptions(CoreServerException.class, CoreTransientException.class)
                .build();
        return Retry.of("fineract-read", config);
    }
}
