package zw.co.innbucks.middleware.corebanking.exception;

import zw.co.innbucks.middleware.corebanking.CoreProvider;

/**
 * The core refused to DEBIT the account a movement draws from because the
 * bank has restricted that account (a full block, or a debit-only block) —
 * a staff decision taken in the core, not a property of this request.
 *
 * <p>Raised ONLY for the account the movement debits. A restriction on the
 * other side of a transfer (the recipient's account is blocked, or refuses
 * credits) is the recipient's state, not the caller's, and stays a plain
 * {@link CoreClientException}: telling a sender "your account is paused"
 * because of someone else's block would be false, and would tell them about
 * the recipient's account in the bargain. Adapters word that refusal with
 * FIXED copy carrying no core code, sentence or account id: a
 * {@code CoreClientException}'s message reaches the caller verbatim (and is
 * replayed from the ledger), so the core's code would tell any sender which
 * hold the bank put on someone else's account. The code belongs in the
 * adapter's log.
 *
 * <p>A {@link CoreClientException}, so everything that treats a client
 * refusal as provably-not-applied (the ledger marks FAILED) keeps doing so.
 * Its message is ops detail for logs and the ledger; customer-facing layers
 * render their own wording and must say no more than that payments from the
 * account are paused — never why.
 */
public class CoreAccountRestrictedException extends CoreClientException {

    public CoreAccountRestrictedException(CoreProvider provider, String message, Throwable cause) {
        super(provider, message, cause);
    }
}
