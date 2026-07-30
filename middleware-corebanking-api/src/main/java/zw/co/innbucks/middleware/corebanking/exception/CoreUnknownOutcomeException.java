package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

/**
 * A WRITE was sent but its fate is unprovable: read timeout mid-flight,
 * connection reset after the request left, or the core answered "under
 * processing". This is a first-class outcome, not an error to mask:
 *
 * <ul>
 *   <li>NEVER retry the write — it may have applied; a retry can move money twice.</li>
 *   <li>Park the ledger row and reconcile via
 *       {@code CoreBankingPort.getTransaction(txRef)} until the core gives a
 *       definitive state.</li>
 *   <li>NEVER auto-expire the parked row — a blocked slot beats a double charge.</li>
 * </ul>
 */
public class CoreUnknownOutcomeException extends CoreBankingException {

    private final TxRef txRef;

    public CoreUnknownOutcomeException(CoreProvider provider, TxRef txRef, String message, Throwable cause) {
        super(provider, message, cause);
        this.txRef = txRef;
    }

    /** The reconciliation handle persisted before the write was sent. */
    public TxRef txRef() {
        return txRef;
    }
}
