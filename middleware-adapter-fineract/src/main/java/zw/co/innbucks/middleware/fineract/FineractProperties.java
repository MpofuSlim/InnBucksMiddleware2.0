package zw.co.innbucks.middleware.fineract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Deployment configuration for the Fineract cell. Two LEAST-PRIVILEGE
 * AppUsers, never one: {@code read*} holds only READ_* permissions (used by
 * profile/balance/reconciliation reads — a leaked reconciliation credential
 * cannot move money); {@code write*} holds exactly the command permissions
 * the adapter calls (never ALL_FUNCTIONS). All credential fields are
 * trimmed before use (stray env-var whitespace is a known footgun).
 *
 * <p>Validation is the adapter's fail-fast: the bean only exists when
 * {@code innbucks.core.provider=fineract}, and then every field here is
 * boot-required.
 */
@Validated
@ConfigurationProperties(prefix = "fineract")
public record FineractProperties(

        /** e.g. https://fineract:8443/fineract-provider/api — no trailing slash, /v1 is appended per call. */
        @NotBlank
        String baseUrl,

        /** Fineract-Platform-TenantId header value; one tenant per (country, core) cell. */
        @NotBlank
        String tenantId,

        @NotBlank
        String readUsername,

        @NotBlank
        String readPassword,

        @NotBlank
        String writeUsername,

        @NotBlank
        String writePassword,

        /** Office the middleware's customers are created under. */
        @NotNull
        Long officeId,

        /** The configured savings product every wallet account is opened on. */
        @NotNull
        Long savingsProductId,

        /**
         * Payment type stamped on every savings deposit/withdrawal.
         * Fineract validates this as {@code notNull()} unconditionally
         * (SavingsAccountTransactionDataValidator.validate), so a cell without
         * it 400s every money movement — boot-required, never defaulted, since
         * the id is per-cell and a wrong one mislabels every transaction.
         */
        @NotNull
        Long paymentTypeId,

        /** The cell's currency; cross-checked against every balance the core reports. */
        @NotBlank
        String currency,

        /** Locale sent in every mutating body — Fineract requires it alongside dates. */
        @NotBlank
        String locale,

        /** Format of every date we send, declared to Fineract in the same body. */
        @NotBlank
        String dateFormat,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout,

        @Valid
        @NotNull
        Resilience resilience
) {

    public record Resilience(
            boolean enabled,
            @Min(1) int slidingWindowSize,
            @Min(1) int minimumNumberOfCalls,
            @Min(1) int failureRateThreshold,
            @NotNull Duration waitDurationInOpenState,
            @Min(1) int permittedCallsInHalfOpenState,
            @NotNull Duration slowCallDurationThreshold,
            @Min(1) int retryMaxAttempts,
            @NotNull Duration retryWaitDuration
    ) {
    }
}
