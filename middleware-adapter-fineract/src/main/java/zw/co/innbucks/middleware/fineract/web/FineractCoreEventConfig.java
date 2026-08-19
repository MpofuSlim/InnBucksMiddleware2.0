package zw.co.innbucks.middleware.fineract.web;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * The pool that takes core-event processing off the Tomcat thread.
 *
 * <p>A single hook fire costs two Fineract reads (account, then transaction),
 * an ownership listing, a name resolution and an SMS round trip — all
 * sequential. Running that inline meant one request thread parked for the whole
 * chain, and a teller posting a batch could occupy several at once while the
 * core they are all waiting on is the same core serving customer traffic.
 *
 * <p><b>Deliberately a SEPARATE pool from the ledger seam's
 * {@code transactionNotificationExecutor}.</b> The two paths have different
 * shapes — this one makes several core calls per message, that one usually
 * none — and different sources. Sharing would let a teller's batch starve the
 * alerts for app-initiated deposits, which are the ones a customer is actively
 * watching.
 *
 * <p><b>Why the MDC decorator is duplicated here rather than shared.</b> This
 * module depends on {@code middleware-corebanking-api} ONLY, never on
 * {@code middleware-core} — an adapter must not reach into core-agnostic
 * application logic. Do not "de-duplicate" this by adding that dependency; the
 * thirty lines are the cheaper price.
 *
 * <p>BOUNDED, and deliberately not {@code CallerRunsPolicy}: caller-runs would
 * hand the work back to the Tomcat thread under exactly the load this pool
 * exists to survive. Saturation drops the event, counts it and shouts.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "innbucks.core.provider", havingValue = "fineract")
public class FineractCoreEventConfig {

    @Bean("coreEventExecutor")
    public Executor coreEventExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("core-event-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setRejectedExecutionHandler(dropAndShout(meterRegistry));
        // Drain on shutdown: the hook has already been answered 200, so an
        // event still queued at deploy time has no other chance of being sent.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    /** Carry correlation id + country across the hop, or the logs orphan. */
    private static TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }

    private static RejectedExecutionHandler dropAndShout(MeterRegistry meterRegistry) {
        return (runnable, executor) -> {
            meterRegistry.counter("innbucks.core.events", "outcome", "dropped").increment();
            log.error("Core event DROPPED — processing queue saturated (pool={}, queued={}). "
                            + "Teller/admin postings are not reaching customers as SMS; "
                            + "check Fineract read latency and the SMS gateway.",
                    executor.getPoolSize(), executor.getQueue().size());
        };
    }
}
