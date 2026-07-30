package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/** Connectivity-level failure PROVABLY before the request reached the core (connect refused/timeout). Safe to retry even for writes. */
public class CoreTransientException extends CoreBankingException {

    public CoreTransientException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
