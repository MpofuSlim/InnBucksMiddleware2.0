package zw.co.innbucks.middleware.limits;

import java.util.Objects;

/**
 * A withdrawal or transfer would break the caller's per-tier limit. Mapped to
 * 422 {@code limit_exceeded} with the {@link LimitRefusal} fields at the top
 * level of the problem body. Thrown before anything is written: from the
 * advisory pre-check (no state at all) or from inside the ledger transaction
 * (which then rolls back — no PENDING row, no core call).
 */
public class PaymentLimitExceededException extends RuntimeException {

    private final LimitRefusal refusal;

    public PaymentLimitExceededException(LimitRefusal refusal) {
        super("Payment limit exceeded: " + Objects.requireNonNull(refusal, "refusal").kind().wireName());
        this.refusal = refusal;
    }

    public LimitRefusal refusal() {
        return refusal;
    }
}
