package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * A transaction's stable reference: middleware-minted (usually the
 * idempotency key), stored on our ledger row BEFORE the write is sent, so an
 * ambiguous outcome is always reconcilable by this ref.
 */
public record TxRef(String reference) {

    public TxRef {
        Objects.requireNonNull(reference, "reference");
        if (reference.isBlank()) {
            throw new IllegalArgumentException("reference must be non-blank");
        }
    }
}
