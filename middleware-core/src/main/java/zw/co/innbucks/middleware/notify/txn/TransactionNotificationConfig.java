package zw.co.innbucks.middleware.notify.txn;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * Wiring for customer transaction alerts.
 *
 * <p>The pool exists so the SMS never sits on the money path. A gateway that
 * takes ten seconds to answer must not add ten seconds to the deposit the
 * customer is watching spin — the movement is already committed by the time
 * this work is queued, so the only thing waiting would be the HTTP response.
 *
 * <p>It is deliberately BOUNDED and deliberately does NOT use
 * {@code CallerRunsPolicy}: caller-runs would push the work back onto the
 * request thread under exactly the load where that hurts most, quietly
 * reintroducing the coupling this pool removes. Saturation instead drops the
 * message, counts it and shouts — a lost receipt is recoverable from the
 * statement; a stalled money path is not.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties(TransactionNotificationProperties.class)
public class TransactionNotificationConfig {

    @Bean
    public TransactionMessageComposer transactionMessageComposer(
            TransactionNotificationProperties properties, CountryProperties countryProperties) {
        ZoneId zone = properties.zone() == null || properties.zone().isBlank()
                ? countryProperties.country().zoneId()
                : ZoneId.of(properties.zone().trim());
        log.info("Transaction alerts: enabled={} zone={} notifyOnFailure={}",
                properties.enabled(), zone, properties.notifyOnFailure());
        return new TransactionMessageComposer(zone, properties.maxLength(), properties.maxNarrativeLength());
    }

    @Bean("transactionNotificationExecutor")
    public Executor transactionNotificationExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("txn-notify-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setRejectedExecutionHandler(dropAndShout(meterRegistry));
        // Let a shutdown finish the messages already queued rather than
        // stranding customers mid-rollout; bounded so it can't hang a deploy.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    /**
     * Carry the correlation id (and country) across the async hop — without
     * this, every transaction-alert log line lands with an empty
     * {@code [country,correlationId,...]} prefix and cannot be tied back to
     * the movement that caused it.
     */
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
            meterRegistry.counter("innbucks.transaction.notifications",
                    "type", "unknown", "leg", "unknown", "outcome", "dropped").increment();
            log.error("Transaction alert DROPPED — notification queue saturated (pool={}, queued={}). "
                            + "Customers are not being told their money moved; check the SMS gateway's latency.",
                    executor.getPoolSize(), executor.getQueue().size());
        };
    }
}
