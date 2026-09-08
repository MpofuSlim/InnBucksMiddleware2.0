package zw.co.innbucks.middleware.statement.loan;

import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One line of a rendered loan statement, CHRONOLOGICAL (oldest first).
 *
 * <p>{@code principalBalanceAfterMinor} is the principal outstanding after
 * this line, SIGNED minor units — the one figure a loan statement anchors on.
 * The interest, fee and penalty portions of a repayment are shown per line;
 * what remains outstanding of each is a POSITION (a snapshot at generation),
 * not something a line carries, and lives on the document.
 *
 * @param reference           an external reference the core holds for the
 *                            transaction (a receipt number, our ref); null
 *                            when it has none
 * @param principalMinor      principal portion of a repayment-shaped line;
 *                            null when the core split no portions (a
 *                            disbursement, a reversed row)
 * @param principalDeltaMinor the SIGNED effect this line had on the principal
 *                            balance — positive for a disbursement, negative
 *                            for the principal portion of a repayment, zero
 *                            for a waiver of interest or a reversed row
 * @param reversed            shown for completeness, moves nothing, feeds no
 *                            total
 */
public record LoanStatementLine(
        String coreId,
        String reference,
        LocalDate date,
        String narrative,
        LoanEntryKind kind,
        long amountMinor,
        Long principalMinor,
        Long interestMinor,
        Long feesMinor,
        Long penaltiesMinor,
        long principalDeltaMinor,
        long principalBalanceAfterMinor,
        boolean reversed
) {

    public LoanStatementLine {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be >= 0, got " + amountMinor);
        }
    }
}
