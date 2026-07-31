package zw.co.innbucks.middleware.corebanking.value;

import java.util.List;
import java.util.Objects;

/**
 * One page of statement entries plus the unpaged total, so a caller can render
 * "showing 20 of 143" without walking every page.
 */
public record TransactionPage(List<TransactionEntry> entries, long totalCount) {

    public TransactionPage {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must be >= 0, got " + totalCount);
        }
    }
}
