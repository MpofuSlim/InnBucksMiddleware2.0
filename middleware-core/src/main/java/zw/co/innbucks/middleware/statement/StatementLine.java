package zw.co.innbucks.middleware.statement;

import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One line of a rendered statement, in CHRONOLOGICAL order (oldest first —
 * the reading order of a printed statement, and the opposite of the paged
 * {@code /me/accounts/{id}/transactions} feed, which is newest first).
 *
 * <p>{@code balanceAfterMinor} is SIGNED minor units: unlike
 * {@code MinorUnits} (whose non-negativity is a port invariant for amounts),
 * a balance can legitimately go negative under fees or an overdraft, and a
 * statement that refused to render that would hide exactly the state the
 * customer most needs to see.
 *
 * @param reference our reconciliation ref — present only for movements made
 *                  through this middleware; null for interest, fees, teller
 *                  entries
 * @param reversed  reversed entries stay ON the statement (a statement is a
 *                  history, not a current view) but are balance-neutral and
 *                  excluded from the credit/debit totals
 */
public record StatementLine(
        String coreId,
        String reference,
        LocalDate date,
        String narrative,
        TransactionDirection direction,
        long amountMinor,
        long balanceAfterMinor,
        boolean reversed
) {

    public StatementLine {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(direction, "direction");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amountMinor must be >= 0, got " + amountMinor);
        }
    }
}
