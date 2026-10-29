package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/**
 * Base of the core-neutral exception taxonomy. Adapters map their core's
 * failures onto these four leaf types plus {@link CoreUnknownOutcomeException};
 * nothing upstream of an adapter ever sees a core-specific exception or a raw
 * upstream body. The operator port adds one more, {@link CoreOperatorAuthException}:
 * the OPERATOR's credential refused, kept apart from {@link CoreAuthException}
 * (ours refused) so the two can never be confused.
 */
public abstract class CoreBankingException extends RuntimeException {

    private final CoreProvider provider;

    protected CoreBankingException(CoreProvider provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public CoreProvider provider() {
        return provider;
    }
}
