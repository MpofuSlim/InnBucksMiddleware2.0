package zw.co.innbucks.middleware.transactions;

/** Customer deposits are switched off in this cell ({@link DepositGate}). Maps to 403 deposit_disabled. */
public class DepositDisabledException extends RuntimeException {

    public DepositDisabledException() {
        super("Customer deposits are disabled in this deployment (innbucks.transactions.deposit.enabled=false)");
    }
}
