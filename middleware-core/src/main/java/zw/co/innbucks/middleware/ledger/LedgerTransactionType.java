package zw.co.innbucks.middleware.ledger;

/** Direction is carried by the type, never by the amount's sign. */
public enum LedgerTransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}
