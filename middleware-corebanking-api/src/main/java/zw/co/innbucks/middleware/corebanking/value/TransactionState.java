package zw.co.innbucks.middleware.corebanking.value;

/**
 * Core-neutral transaction state. {@link #UNKNOWN} is a first-class outcome:
 * the core was asked but could not (yet) prove the write's fate. Rows in that
 * state are parked and reconciled — never retried, never auto-expired.
 */
public enum TransactionState {
    COMPLETED,
    PENDING,
    FAILED,
    UNKNOWN
}
