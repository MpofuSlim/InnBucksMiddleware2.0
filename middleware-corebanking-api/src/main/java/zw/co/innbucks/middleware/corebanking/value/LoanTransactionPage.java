package zw.co.innbucks.middleware.corebanking.value;

import java.util.List;
import java.util.Objects;

/**
 * One page of a loan's transactions, NEWEST FIRST with a deterministic
 * tiebreak (the port contract every adapter owes, so a caller can walk back
 * from today to the period it wants and stop). {@code totalCount} is NULLABLE
 * with the same meaning as on {@link TransactionPage}: unknown, never zero.
 * Callers page until a page comes back shorter than they asked for.
 */
public record LoanTransactionPage(List<LoanTransactionEntry> entries, Long totalCount) {

    public LoanTransactionPage {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        if (totalCount != null && totalCount < 0) {
            throw new IllegalArgumentException("totalCount must be >= 0 when present, got " + totalCount);
        }
    }
}
