package zw.co.innbucks.middleware.corebanking.value;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One line of a loan's transaction history, as the CORE recorded it — the
 * loan counterpart of {@link TransactionEntry}.
 *
 * <p>A loan has no single "balance": the borrower owes principal, interest,
 * fees and penalties, each moving independently. The statement anchors on
 * the one figure every core tracks per transaction — the PRINCIPAL
 * outstanding after it — and reports the other portions per line.
 *
 * @param coreId                the core's own transaction id — always present
 * @param externalRef           an external reference the core holds for the
 *                              transaction; null when it has none
 * @param narrative             the core's label for the entry ("Repayment",
 *                              "Disbursement") — display text, never parsed
 * @param kind                  which total the line feeds; see {@link LoanEntryKind}
 * @param amount                the transaction's total amount, non-negative
 * @param principalPortion      how much of the amount was principal (null when
 *                              the core does not split it — a reversed row,
 *                              or a kind that has no portions)
 * @param principalDeltaMinor   the SIGNED effect this entry had on the principal
 *                              outstanding, in minor units, as the ADAPTER
 *                              derived it from the core's type and portions
 *                              (+amount for a disbursement, −principal portion
 *                              for a repayment, 0 for a {@code NONE} kind).
 *                              Zero for a reversed entry
 * @param principalBalanceAfter the core's own principal outstanding AFTER
 *                              this entry; null when the core does not report
 *                              one (reversed rows and bookkeeping kinds
 *                              typically carry none). Signed minor units — an
 *                              overpaid loan can go below zero in some cores
 * @param reversed              reversed entries stay on the statement (it is
 *                              a history) but move nothing and feed no total
 */
public record LoanTransactionEntry(
        String coreId,
        String externalRef,
        LocalDate valueDate,
        String narrative,
        LoanEntryKind kind,
        MinorUnits amount,
        MinorUnits principalPortion,
        MinorUnits interestPortion,
        MinorUnits feePortion,
        MinorUnits penaltyPortion,
        long principalDeltaMinor,
        Long principalBalanceAfter,
        boolean reversed
) {

    public LoanTransactionEntry {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        if (reversed && principalDeltaMinor != 0) {
            throw new IllegalArgumentException("a reversed entry has no principal effect");
        }
        if (kind == LoanEntryKind.NONE && principalDeltaMinor != 0) {
            throw new IllegalArgumentException("a NONE entry has no principal effect");
        }
    }
}
