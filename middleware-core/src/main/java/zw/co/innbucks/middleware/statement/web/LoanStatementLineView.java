package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/** One line of the JSON loan statement rendering. Oldest first — reading order. */
@Schema(description = "One loan statement line, as recorded by the core banking system. Oldest first.")
public record LoanStatementLineView(

        @Schema(description = "The core's transaction id. Stable, and what support will ask for.",
                example = "902")
        String id,

        @Schema(description = "External reference the core holds for the transaction (a receipt "
                + "number, a payment reference); null when it has none.",
                example = "rcpt-4411", nullable = true)
        String reference,

        @Schema(description = "Value date.", example = "2026-08-01")
        LocalDate date,

        @Schema(description = "Display label for the entry. Show it; do not parse it.",
                example = "Repayment")
        String narrative,

        @Schema(description = "Which total the line feeds. DISBURSEMENT raises the principal balance; "
                + "REPAYMENT lowers it by principalMinor and carries the interest/fee/penalty split; "
                + "WAIVER and WRITE_OFF are amounts forgiven; OTHER is a balance-moving entry that "
                + "totals nothing (a refund, a chargeback); NONE is bookkeeping that moves nothing "
                + "(a charge-off classification, a transfer marker) — render it muted.",
                example = "REPAYMENT",
                allowableValues = {"DISBURSEMENT", "REPAYMENT", "WAIVER", "WRITE_OFF", "OTHER", "NONE"})
        String kind,

        @Schema(description = "The transaction's total amount in MINOR units. Always positive.",
                example = "47500")
        long amountMinor,

        @Schema(description = "Principal portion of the amount; null when the core split no portions "
                + "(a disbursement, a reversed row).", example = "41667", nullable = true)
        Long principalMinor,

        @Schema(example = "5500", nullable = true) Long interestMinor,

        @Schema(example = "333", nullable = true) Long feesMinor,

        @Schema(example = "0", nullable = true) Long penaltiesMinor,

        @Schema(description = "The SIGNED effect on the principal balance, MINOR units: positive for a "
                + "disbursement, negative for the principal portion of a repayment, zero for an "
                + "interest waiver, a NONE line or a reversed line.", example = "-41667")
        long principalDeltaMinor,

        @Schema(description = "Principal outstanding after this line, MINOR units, SIGNED.",
                example = "375000")
        long principalBalanceAfterMinor,

        @Schema(description = "True if the entry was reversed. Reversed entries stay on the statement, "
                + "move nothing, and are excluded from every total.", example = "false")
        boolean reversed
) {
}
