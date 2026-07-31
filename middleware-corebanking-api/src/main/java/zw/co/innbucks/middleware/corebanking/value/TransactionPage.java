package zw.co.innbucks.middleware.corebanking.value;

import java.util.List;
import java.util.Objects;

/**
 * One page of statement entries, plus the unpaged total when the core reports
 * one — so a caller can render "showing 20 of 143" without walking every page.
 *
 * <p>{@code totalCount} is NULLABLE, and the null case is real: a live
 * Fineract cell returned a perfectly good page of transactions with no count
 * field at all. Null means <b>unknown</b>, never zero — a statement that
 * claimed a customer had no history when the core simply hadn't been asked for
 * a total would hide their own money from them. Callers that page should stop
 * when a page comes back SHORTER than the limit they asked for, not when they
 * reach {@code totalCount}.
 */
public record TransactionPage(List<TransactionEntry> entries, Long totalCount) {

    public TransactionPage {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        if (totalCount != null && totalCount < 0) {
            throw new IllegalArgumentException("totalCount must be >= 0 when present, got " + totalCount);
        }
    }
}
