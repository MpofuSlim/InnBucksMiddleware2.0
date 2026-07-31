package zw.co.innbucks.middleware.notify.txn;

import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;

import java.time.Instant;
import java.util.UUID;

/**
 * A money movement reached a TERMINAL state. Published from
 * {@code LedgerService.transition()} — the single lifecycle chokepoint — so
 * it fires for the request-thread outcome AND for a row the reconciler
 * resolves hours later, with no second place to remember.
 *
 * <p>An immutable snapshot, deliberately: the listener runs on another thread
 * after the ledger transaction commits, and must not re-read a row that may
 * have moved on (or, in a test, been truncated) since.
 *
 * <p>Only {@link LedgerStatus#COMPLETED} and {@link LedgerStatus#FAILED} are
 * published. SUBMITTED is bookkeeping and UNKNOWN means we cannot prove what
 * happened — telling a customer either way would be a lie, and an UNKNOWN row
 * that later reconciles publishes its real outcome then.
 */
public record SettledMovementEvent(
        UUID transactionId,
        UUID customerId,
        LedgerTransactionType type,
        LedgerStatus status,
        String sourceAccount,
        String destinationAccount,
        long amountMinor,
        String currency,
        String narrative,
        String externalRef,
        String coreTxRef,
        Instant occurredAt
) {

    public boolean completed() {
        return status == LedgerStatus.COMPLETED;
    }

    /**
     * The reference a customer can quote to support. The core's own id when it
     * gave us one (short, and what an operator sees in the core's UI); our
     * external ref's leading bytes otherwise — the full ref is a 64-char
     * SHA-256 digest, unreadable over SMS.
     */
    public String customerFacingRef() {
        if (coreTxRef != null && !coreTxRef.isBlank()) {
            return coreTxRef.trim();
        }
        if (externalRef == null || externalRef.isBlank()) {
            return transactionId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        }
        String ref = externalRef.trim();
        return (ref.length() <= 8 ? ref : ref.substring(0, 8)).toUpperCase(java.util.Locale.ROOT);
    }
}
