package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/** One line of the JSON statement rendering. Oldest first — reading order. */
@Schema(description = "One statement line, as recorded by the core banking system. Oldest first.")
public record StatementLineView(

        @Schema(description = "The core's transaction id. Stable, and what support will ask for.",
                example = "13")
        String id,

        @Schema(description = "Our own reference — present only for movements made through this API. "
                + "Null for interest postings, fees or anything booked directly on the core.",
                example = "37130855-654b-4fc7-a83d-465f7aaba5df", nullable = true)
        String reference,

        @Schema(description = "Value date (UTC).", example = "2026-08-03")
        LocalDate date,

        @Schema(description = "Display label for the entry. Show it; do not parse it.",
                example = "Deposit")
        String narrative,

        @Schema(description = "Direction from this account's point of view.",
                example = "CREDIT", allowableValues = {"CREDIT", "DEBIT"})
        String direction,

        @Schema(description = "Amount in MINOR units (cents). Always positive — direction carries the sign.",
                example = "5000")
        long amountMinor,

        @Schema(description = "Balance after this entry in MINOR units, SIGNED — unlike the paged "
                + "transactions feed this is never null: the statement is only produced once every "
                + "balance is anchored.", example = "15000")
        long balanceAfterMinor,

        @Schema(description = "True if the entry was reversed. Reversed entries stay on the statement, "
                + "do not move the balance, and are excluded from the money-in/out totals.",
                example = "false")
        boolean reversed
) {
}
