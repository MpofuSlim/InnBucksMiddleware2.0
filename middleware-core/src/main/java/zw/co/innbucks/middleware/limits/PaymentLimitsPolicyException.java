package zw.co.innbucks.middleware.limits;

/**
 * The limits policy (packaged file plus any emergency overrides) is missing,
 * malformed or inconsistent. Thrown while the policy bean is built, so it
 * REFUSES BOOT: a cell must never take payments under limits nobody can read.
 * The message names the file and the offending value — it is the operator's
 * whole diagnosis.
 */
public class PaymentLimitsPolicyException extends IllegalStateException {

    public PaymentLimitsPolicyException(String message) {
        super(message);
    }

    public PaymentLimitsPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
