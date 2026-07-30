package zw.co.innbucks.middleware.auth.exception;

/**
 * Thrown when the verification token is valid but the customer's current
 * state does not permit the requested operation — e.g. trying to /auth/pin/set
 * (PIN_SETUP purpose) on an already-ACTIVE customer, or /auth/pin/reset on a
 * PENDING_VERIFICATION customer who never set a PIN to begin with.
 */
public class PinSetNotAllowedException extends AuthException {

    public PinSetNotAllowedException(String reason) {
        super("PIN-set rejected: " + reason);
    }
}
