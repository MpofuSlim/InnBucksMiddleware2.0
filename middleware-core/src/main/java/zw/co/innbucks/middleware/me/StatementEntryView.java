package zw.co.innbucks.middleware.me;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "One line on the customer's statement, as recorded by the core banking system.")
public record StatementEntryView(

        @Schema(description = "The core's transaction id. Stable, and what support will ask for.",
                example = "13")
        String id,

        @Schema(description = "Our own reference — present only for movements made through this API "
                + "(it is the value returned as transactionId). Null for interest postings, fees or "
                + "anything booked directly on the core.",
                example = "37130855-654b-4fc7-a83d-465f7aaba5df", nullable = true)
        String reference,

        @Schema(description = "Direction from this account's point of view.",
                example = "DEBIT", allowableValues = {"CREDIT", "DEBIT"})
        String direction,

        @Schema(description = "Display label for the entry. Show it; do not parse it.",
                example = "Withdrawal")
        String narrative,

        @Schema(description = "Amount in MINOR units (cents). Always positive — direction carries the sign.",
                example = "1000")
        long amountMinor,

        @Schema(description = "Balance after this entry, in MINOR units. Null when the core does not "
                + "report one for this entry type.", example = "1500", nullable = true)
        Long runningBalanceMinor,

        @Schema(description = "Value date (UTC).", example = "2026-07-31")
        LocalDate date,

        @Schema(description = "True if the entry was reversed. Reversed entries stay on the statement "
                + "by design — a statement is a history, not a current view.", example = "false")
        boolean reversed
) {
}
