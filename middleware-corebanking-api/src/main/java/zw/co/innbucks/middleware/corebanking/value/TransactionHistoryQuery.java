package zw.co.innbucks.middleware.corebanking.value;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A statement request against one account, addressed EITHER by the account's
 * stable external reference (every account this middleware opened) OR by the
 * core's own account id ({@code coreAccountId} — the id an operator quotes,
 * covering branch-created accounts that carry no external reference).
 * Exactly one of the two is set.
 *
 * <p>Both addressings MUST answer with the same entries, ordering and
 * envelope: they are two keys to one account, never two queries. An adapter
 * that provides a {@link zw.co.innbucks.middleware.corebanking.CoreOperatorPort}
 * must support the core-id addressing (the operator surface is where such
 * accounts appear); adapters without one may reject it.
 *
 * <p>{@code from}/{@code to} are inclusive value dates and may be null for an
 * open-ended range. {@code limit} is bounded by the caller (the web layer caps
 * it) — an adapter must never issue an unbounded query, because a long-lived
 * wallet's full history is not a thing to pull into memory.
 */
public record TransactionHistoryQuery(
        AccountRef account,
        String coreAccountId,
        LocalDate from,
        LocalDate to,
        int offset,
        int limit
) {

    public TransactionHistoryQuery {
        if ((account == null) == (coreAccountId == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of account (external reference) or coreAccountId must be set");
        }
        if (coreAccountId != null && coreAccountId.isBlank()) {
            throw new IllegalArgumentException("coreAccountId must be non-blank");
        }
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

    /** The original shape: addressed by external reference. */
    public TransactionHistoryQuery(AccountRef account, LocalDate from, LocalDate to, int offset, int limit) {
        this(Objects.requireNonNull(account, "account"), null, from, to, offset, limit);
    }

    /** Addressed by the core's own account id (e.g. a Fineract savings id). */
    public static TransactionHistoryQuery byCoreAccountId(String coreAccountId, LocalDate from,
                                                          LocalDate to, int offset, int limit) {
        return new TransactionHistoryQuery(null, coreAccountId, from, to, offset, limit);
    }
}
