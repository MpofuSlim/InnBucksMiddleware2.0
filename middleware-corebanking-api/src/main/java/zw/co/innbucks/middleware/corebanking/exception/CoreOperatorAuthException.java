package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/**
 * The core rejected an OPERATOR's own credential, presented through
 * {@link zw.co.innbucks.middleware.corebanking.CoreOperatorPort} — a person at
 * the back-office console typed a wrong password, or their session is gone.
 *
 * <p>Deliberately NOT a {@link CoreAuthException}. That type means the core
 * refused THIS MIDDLEWARE's service credentials or a permission they lack: an
 * operations fault, logged at ERROR, never the caller's doing. A console
 * statement makes both kinds of call (it authorises as the operator, then reads
 * deposit transactions with the middleware's read-only service account), so if
 * the two shared a type, a provisioning gap on the service account would tell
 * the operator their own credential was rejected, and they would reset a
 * password that was never the problem. Separate types keep "who was refused"
 * in the type, where no handler can guess it wrong.
 */
public class CoreOperatorAuthException extends CoreBankingException {

    public CoreOperatorAuthException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
