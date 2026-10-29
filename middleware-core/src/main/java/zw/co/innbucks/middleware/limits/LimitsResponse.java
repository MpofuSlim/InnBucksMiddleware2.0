package zw.co.innbucks.middleware.limits;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "LimitsResponse",
        description = "The caller's payment limits and what is left of them now. Withdrawals and "
                + "transfers made through the app count; deposits do not. Amounts are MINOR units of "
                + "`currency`. Computed with the same rules and the same ledger query the payment "
                + "endpoints refuse with, so a payment of `sendableNowMinor` passes and one more "
                + "minor unit is refused (barring a concurrent payment).")
public record LimitsResponse(

        @Schema(description = "The KYC tier the limits are for.", example = "basic",
                allowableValues = {"basic", "standard", "enhanced"})
        String kycTier,

        @Schema(description = "Currency of every *Minor value (the cell currency).", example = "USD")
        String currency,

        @Schema(description = "Withdrawals and transfers AT OR ABOVE this amount need SMS approval "
                + "(403 step_up_required). Null when step-up is switched off.",
                example = "50000", nullable = true)
        Long stepUpAtOrAboveMinor,

        @Schema(description = "The largest single payment that would pass every configured limit right "
                + "now: the smallest of per_payment, the daily remainder and the monthly remainder; 0 when "
                + "today's payment count is used up. NULL when no money limit is configured for this tier "
                + "(do not render a number then — there is no cap to show).",
                example = "37500", nullable = true)
        Long sendableNowMinor,

        @Schema(description = "Only the limits configured for this tier; a kind the tier does not have is "
                + "left out. Empty when no limit applies.")
        List<LimitView> limits
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "LimitView",
            description = "One limit. per_payment carries limitMinor only; daily and monthly carry the money "
                    + "fields and resetsAt; daily_count carries limit/used/remaining and resetsAt.")
    public record LimitView(

            @Schema(description = "Which limit.", example = "daily",
                    allowableValues = {"per_payment", "daily", "monthly", "daily_count"})
            String kind,

            @Schema(description = "The limit, minor units (money kinds).", example = "150000")
            Long limitMinor,

            @Schema(description = "Sent in the period so far — SUCCESS and PROCESSING payments, never "
                    + "FAILED ones (daily/monthly).", example = "112500")
            Long usedMinor,

            @Schema(description = "The part of usedMinor still PROCESSING. It counts; if it later fails it "
                    + "stops counting (daily/monthly).", example = "0")
            Long processingMinor,

            @Schema(description = "limitMinor - usedMinor, never below 0 (daily/monthly).", example = "37500")
            Long remainingMinor,

            @Schema(description = "Payments allowed per day (daily_count).", example = "10")
            Long limit,

            @Schema(description = "Withdrawals + transfers made today, any status but FAILED (daily_count).",
                    example = "3")
            Long used,

            @Schema(description = "limit - used, never below 0 (daily_count).", example = "7")
            Long remaining,

            @Schema(description = "When the period's allowance comes back, ISO-8601 at the market offset "
                    + "(daily, monthly, daily_count). Show it as is; do no time-zone arithmetic.",
                    example = "2026-10-29T00:00:00+02:00")
            String resetsAt
    ) {

        static LimitView perPayment(long limitMinor) {
            return new LimitView(LimitKind.PER_PAYMENT.wireName(), limitMinor,
                    null, null, null, null, null, null, null);
        }

        static LimitView money(LimitKind kind, long limitMinor, long usedMinor, long processingMinor,
                               String resetsAt) {
            return new LimitView(kind.wireName(), limitMinor, usedMinor, processingMinor,
                    Math.max(0L, limitMinor - usedMinor), null, null, null, resetsAt);
        }

        static LimitView count(long limit, long used, String resetsAt) {
            return new LimitView(LimitKind.DAILY_COUNT.wireName(), null, null, null, null,
                    limit, used, Math.max(0L, limit - used), resetsAt);
        }
    }
}
