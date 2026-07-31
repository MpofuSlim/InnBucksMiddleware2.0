package zw.co.innbucks.middleware.corebanking.value;

/**
 * Which way money moved from the ACCOUNT's point of view. Deliberately not
 * {@code MovementKind}: a statement carries entries we never initiated —
 * interest postings, fees, teller corrections — which have no movement kind
 * but always have a direction.
 */
public enum TransactionDirection {
    CREDIT,
    DEBIT
}
