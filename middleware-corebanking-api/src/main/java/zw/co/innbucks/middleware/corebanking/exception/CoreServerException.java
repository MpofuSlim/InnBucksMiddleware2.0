package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/** The core failed (5xx) on a READ or before a write was sent. Reads may retry; for an in-flight write see CoreUnknownOutcomeException. */
public class CoreServerException extends CoreBankingException {

    public CoreServerException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
