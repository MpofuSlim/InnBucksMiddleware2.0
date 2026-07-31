package zw.co.innbucks.middleware.notify.txn;

import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;

import java.util.UUID;

/**
 * One side of a movement, as one customer experiences it. A deposit or a
 * withdrawal has exactly one leg; a transfer has two — the sender's debit and
 * the recipient's credit — and the recipient's leg only exists when the
 * destination account belongs to a customer of this cell.
 *
 * @param customerId    who to tell
 * @param accountId     the account THIS customer's message is about (core externalId)
 * @param counterparty  the other account in the movement, or null for a
 *                      deposit/withdrawal where there isn't one
 */
public record MovementLeg(
        UUID customerId,
        String accountId,
        String counterparty,
        TransactionDirection direction
) {
}
