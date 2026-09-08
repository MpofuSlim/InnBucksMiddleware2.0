package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.middleware.statement.StatementDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON rendering of a {@link StatementDocument} — same numbers as the PDF
 * and CSV renderings by construction, because all three are produced from the
 * one assembled document.
 */
@Schema(description = "A fixed-period account statement with anchored opening/closing balances. "
        + "The same document is available as PDF or CSV via ?format=.")
public record StatementResponse(

        @Schema(description = "The account this statement covers.",
                example = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet")
        String accountId,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "Best-effort display name from the core; null when the core has no "
                + "profile. The MSISDN identifies the holder either way.",
                example = "Tariro Mpofu", nullable = true)
        String customerName,

        @Schema(description = "The holder's registered mobile number.", example = "+263771234567")
        String msisdn,

        @Schema(description = "Inclusive period start (value dates, UTC).", example = "2026-08-01")
        LocalDate from,

        @Schema(description = "Inclusive period end (value dates, UTC).", example = "2026-08-31")
        LocalDate to,

        @Schema(description = "When this document was assembled (UTC instant).")
        Instant generatedAt,

        @Schema(description = "Balance before the first entry of the period, MINOR units, SIGNED. "
                + "Anchored on the core's own running balance, never derived by summing.",
                example = "10000")
        long openingBalanceMinor,

        @Schema(description = "Balance after the last entry of the period, MINOR units, SIGNED.",
                example = "15000")
        long closingBalanceMinor,

        @Schema(description = "Sum of credits in the period that moved money (not reversed, not "
                + "balance-neutral), MINOR units. openingBalanceMinor + totalCreditsMinor − "
                + "totalDebitsMinor always equals closingBalanceMinor.", example = "6000")
        long totalCreditsMinor,

        @Schema(description = "Sum of debits in the period that moved money (not reversed, not "
                + "balance-neutral), MINOR units.", example = "1000")
        long totalDebitsMinor,

        List<StatementLineView> lines
) {

    public static StatementResponse of(StatementDocument d) {
        return new StatementResponse(
                d.accountId(), d.currencyCode(), d.customerName(), d.msisdn(),
                d.from(), d.to(), d.generatedAt(),
                d.openingBalanceMinor(), d.closingBalanceMinor(),
                d.totalCreditsMinor(), d.totalDebitsMinor(),
                d.lines().stream()
                        .map(l -> new StatementLineView(l.coreId(), l.reference(), l.date(),
                                l.narrative(), l.direction().name(), l.amountMinor(),
                                l.balanceAfterMinor(), l.reversed(), l.balanceNeutral()))
                        .toList());
    }
}
