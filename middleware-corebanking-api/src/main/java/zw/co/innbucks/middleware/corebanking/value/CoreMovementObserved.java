package zw.co.innbucks.middleware.corebanking.value;

import java.time.Instant;
import java.util.Objects;

/**
 * Money moved in the core WITHOUT this middleware asking it to — a teller or
 * admin posting at the branch, an interest run, a correction.
 *
 * <p>The middleware's own ledger cannot see these: it only holds movements it
 * initiated. So a customer who receives an over-the-counter deposit would
 * otherwise be told nothing, which is the gap this exists to close.
 *
 * <p>Core-neutral by construction. An adapter translates whatever its core
 * emits — a Fineract web hook, a Veengu callback — into this shape, so nothing
 * downstream learns a core-specific field name or numeric id.
 *
 * @param accountExternalId the account that moved, by the stable reference the
 *        rest of the system uses (never a core-internal numeric id)
 * @param externalRef the transaction reference the core recorded, when there
 *        is one. For movements THIS middleware initiated it is our own ledger
 *        ref, which is exactly how a duplicate alert is avoided — see the
 *        listener. Null for anything booked directly in the core.
 * @param coreTxRef the core's own id for the transaction, for support to quote
 */
public record CoreMovementObserved(
        String accountExternalId,
        TransactionDirection direction,
        MinorUnits amount,
        String narrative,
        String externalRef,
        String coreTxRef,
        Instant occurredAt
) {

    public CoreMovementObserved {
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** True when this movement carries a reference the middleware itself minted. */
    public boolean hasOurReference() {
        return externalRef != null && !externalRef.isBlank();
    }
}
