package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/** Outcome of a money movement, keyed by our reconciliation ref. */
public record TransactionResult(TxRef ref, TransactionState state) {

    public TransactionResult {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(state, "state");
    }
}
