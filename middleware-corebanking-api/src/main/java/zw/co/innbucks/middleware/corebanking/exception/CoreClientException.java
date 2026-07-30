package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/** The core rejected the request as invalid (4xx other than auth): bad input, unknown account, business-rule veto. Not retryable. */
public class CoreClientException extends CoreBankingException {

    public CoreClientException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
