package zw.co.innbucks.middleware.fineract;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An OPEN breaker must surface as the core-neutral
 * {@link CoreTransientException}, never as Resilience4j's raw
 * {@link CallNotPermittedException} — which nothing upstream maps, so it
 * answered 500 and parked a never-sent movement as UNKNOWN. Built through the
 * public constructor so the real breaker/retry configuration is what runs.
 */
class FineractResilienceTest {

    private FineractResilience resilience;
    private final AtomicInteger invocations = new AtomicInteger();

    @BeforeEach
    void openTheBreaker() {
        // Window of one, threshold 50%: a single recorded failure opens it.
        // The open wait is long enough that it cannot drift to half-open
        // mid-test.
        resilience = new FineractResilience(new FineractProperties.Resilience(true, 1, 1, 50,
                Duration.ofMinutes(5), 1, Duration.ofSeconds(8), 3, Duration.ofMillis(10)));
        assertThatThrownBy(() -> resilience.write(() -> {
            throw new CoreServerException(CoreProvider.FINERACT, "Fineract write failed (500)", null);
        })).isInstanceOf(CoreServerException.class);
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openBreakerRefusesAWriteAsTransientWithoutSendingIt() {
        assertThatThrownBy(() -> resilience.write(this::countedCall))
                .isInstanceOfSatisfying(CoreTransientException.class,
                        ex -> assertThat(ex.provider()).isEqualTo(CoreProvider.FINERACT))
                .hasCauseInstanceOf(CallNotPermittedException.class);

        assertThat(invocations.get()).isZero();
    }

    @Test
    void openBreakerRefusesAReadAsTransientWithoutSendingOrRetryingIt() {
        assertThatThrownBy(() -> resilience.read(this::countedCall))
                .isInstanceOf(CoreTransientException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);

        // Zero, not three: the retry sits inside the breaker and never ran.
        assertThat(invocations.get()).isZero();
    }

    @Test
    void theTranslatedRefusalIsNeverRecordedAsAFailure() {
        assertThatThrownBy(() -> resilience.write(this::countedCall))
                .isInstanceOf(CoreTransientException.class);
        assertThatThrownBy(() -> resilience.read(this::countedCall))
                .isInstanceOf(CoreTransientException.class);

        // Counted as refused calls only — the one real failure that opened the
        // breaker is still the only failure it holds.
        CircuitBreaker.Metrics metrics = resilience.circuitBreaker().getMetrics();
        assertThat(metrics.getNumberOfNotPermittedCalls()).isEqualTo(2);
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private String countedCall() {
        invocations.incrementAndGet();
        return "ok";
    }
}
