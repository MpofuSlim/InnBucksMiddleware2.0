package zw.co.innbucks.middleware.auth.exception;

/**
 * Thrown when a customer's identity is recognised (MSISDN found) but they have
 * not yet completed the OTP-gated PIN-setup flow. Mapped to 403 pin_not_set so
 * the mobile app can route the user to the OTP+PIN flow instead of showing a
 * generic "invalid credentials" screen.
 *
 * <p>Yes, this leaks "the MSISDN is registered" to an anonymous caller on
 * {@code /auth/login}. Since registration moved to the S2S
 * {@code /internal/customers} path (user-service is the only caller), the
 * mobile app no longer has guaranteed knowledge of "this MSISDN was just
 * registered" — so the older justification of "the registering channel
 * already knows" no longer holds. Tracked as an IMPORTANT-tier
 * account-enumeration item: collapse 403 pin_not_set + 423 account_locked
 * + 429 backoff to 401 invalid_credentials for unauthenticated callers,
 * and only expose the distinct surfaces after an OTP-issued challenge
 * token has proven identity.
 */
public class PinNotSetException extends AuthException {

    public PinNotSetException() {
        super("Customer has not completed PIN setup");
    }
}
