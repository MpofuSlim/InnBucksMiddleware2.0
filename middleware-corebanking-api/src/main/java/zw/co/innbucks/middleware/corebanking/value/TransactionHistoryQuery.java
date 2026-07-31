package zw.co.innbucks.middleware.corebanking.value;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A statement request against one account.
 *
 * <p>{@code from}/{@code to} are inclusive value dates and may be null for an
 * open-ended range. {@code limit} is bounded by the caller (the web layer caps
 * it) — an adapter must never issue an unbounded query, because a long-lived
 * wallet's full history is not a thing to pull into memory.
 */
public record TransactionHistoryQuery(
        AccountRef account,
        LocalDate from,
        LocalDate to,
        int offset,
        int limit
) {

    public TransactionHistoryQuery {
        Objects.requireNonNull(account, "account");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("'to' (" + to + ") is before 'from' (" + from + ")");
        }
    }
}
