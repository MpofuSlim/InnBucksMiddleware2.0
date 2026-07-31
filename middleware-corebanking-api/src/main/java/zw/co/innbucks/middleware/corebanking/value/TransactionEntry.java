package zw.co.innbucks.middleware.corebanking.value;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One line on a customer statement, as the CORE recorded it.
 *
 * <p>The core is the source of truth here, not our ledger: an account accrues
 * interest postings, fees and teller corrections that never crossed this
 * middleware, and a statement that omitted them would not reconcile against
 * the customer's actual balance.
 *
 * @param coreId      the core's own transaction id — always present
 * @param externalRef our reconciliation ref, present only for movements that
 *                    came through this middleware; null for everything else
 * @param runningBalance balance after this entry; null when the core does not
 *                    report one (it is not guaranteed for every entry type)
 * @param narrative   the core's label for the entry (e.g. "Deposit",
 *                    "Interest Posting") — display text, never parsed
 */
public record TransactionEntry(
        String coreId,
        String externalRef,
        TransactionDirection direction,
        String narrative,
        MinorUnits amount,
        MinorUnits runningBalance,
        LocalDate valueDate,
        boolean reversed
) {

    public TransactionEntry {
        Objects.requireNonNull(coreId, "coreId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(valueDate, "valueDate");
    }
}
