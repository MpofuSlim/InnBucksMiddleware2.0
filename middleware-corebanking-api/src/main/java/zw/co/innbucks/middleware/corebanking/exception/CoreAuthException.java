package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/**
 * The middleware's own credentials were rejected by the core (401/403) — an ops
 * incident, never the customer's fault. Never thrown for an OPERATOR's
 * credential: that is {@link CoreOperatorAuthException}.
 */
public class CoreAuthException extends CoreBankingException {

    public CoreAuthException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
