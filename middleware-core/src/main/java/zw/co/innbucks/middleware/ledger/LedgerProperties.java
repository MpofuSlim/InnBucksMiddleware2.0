package zw.co.innbucks.middleware.ledger;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "innbucks.ledger")
public record LedgerProperties(

        /** PENDING rows older than this are presumed crash-orphaned and parked as UNKNOWN. */
        @NotNull
        Duration stalePendingThreshold,

        /** Max rows per reconciliation sweep. */
        @Min(1)
        int batchSize,

        /** First reconcile-retry delay; doubles per attempt. */
        @NotNull
        Duration reconcileBackoffBase,

        /** Ceiling for the doubled backoff. */
        @NotNull
        Duration reconcileBackoffCap,

        /** UNKNOWN rows older than this trip the operator alarm every sweep. */
        @NotNull
        Duration parkedAlertThreshold
) {
}
