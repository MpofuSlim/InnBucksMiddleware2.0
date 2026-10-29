package zw.co.innbucks.middleware.auth.exception;

/**
 * A session refresh refused because the bank has FULLY blocked the customer's
 * account in the core. Thrown only after every refresh family the customer
 * holds has been revoked, so the answer is final for every device, not just
 * the one asking. Rendered 403 {@code account_restricted} with fixed copy —
 * why the bank blocked the account is the bank's to tell.
 */
public class AccountRestrictedSessionException extends AuthException {

    public AccountRestrictedSessionException() {
        super("Session refused: the core reports the customer's account fully blocked");
    }
}
