package zw.co.innbucks.middleware.corebanking.value;

/** What kind of movement a reconciliation lookup refers to — it decides WHERE the adapter can query. */
public enum MovementKind {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}
