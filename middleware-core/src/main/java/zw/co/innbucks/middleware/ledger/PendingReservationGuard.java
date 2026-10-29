package zw.co.innbucks.middleware.ledger;

import java.time.Instant;

/**
 * A check that runs INSIDE the transaction that opens a PENDING ledger row,
 * immediately before the INSERT — the hook that makes "the PENDING row is the
 * reservation" atomic. The per-tier payment limits use it to lock the
 * customer, sum what they have already sent and refuse or let the INSERT go
 * ahead, all in that one transaction.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li>Throw to refuse: the transaction rolls back, no row is written, and
 *       because {@link LedgeredMovementExecutor} opens the row before its try
 *       block, no core call is made either.</li>
 *   <li>NO network call — the transaction holds a pooled connection (and,
 *       for the limits, a per-customer lock) until it commits.</li>
 *   <li>Use the shared {@code JdbcTemplate}/DataSource so the statements
 *       join this transaction.</li>
 * </ul>
 */
@FunctionalInterface
public interface PendingReservationGuard {

    /** No check — deposits, and every pre-limits caller. */
    PendingReservationGuard NONE = (draft, openedAt) -> { };

    /**
     * @param draft    the row about to be inserted
     * @param openedAt the instant that will be stamped as its {@code created_at},
     *                 so a period boundary is judged on exactly the value stored
     */
    void check(LedgerDraft draft, Instant openedAt);
}
