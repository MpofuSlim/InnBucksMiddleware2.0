package zw.co.innbucks.middleware.corebanking;

import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;

/**
 * Back-office reads performed WITH THE OPERATOR'S OWN core credential — the
 * inverse trust direction of {@link CoreBankingPort}, which always acts as
 * this middleware's service accounts.
 *
 * <p><b>Why this exists.</b> The back-office console (the Mifos web app) is a
 * browser SPA holding the operator's core credentials and nothing else, and
 * this middleware deliberately has no operator identity of its own. So when
 * the console needs something only the middleware can produce (a rendered
 * bank statement), authentication AND authorisation are DELEGATED to the
 * core: the adapter presents the operator's credential to its own core and
 * lets the core answer "who is this, and may they read this account?". The
 * middleware never grows a user table, a session store or a permission model
 * for operators — the core's is the only one.
 *
 * <p><b>Contract for implementations:</b>
 * <ul>
 *   <li>The credential is forwarded ONLY to the adapter's own core, over the
 *       private cell network — never stored, never logged, never sent
 *       anywhere else.</li>
 *   <li>A rejected credential throws {@link CoreAuthException}; an account
 *       the operator may not read — or that does not exist — throws
 *       {@link CoreClientException}. Implementations should not distinguish
 *       the last two more than their core already does.</li>
 *   <li>This is a READ authorisation. Nothing behind this port may ever
 *       mutate core state on an operator's behalf — operator ACTIONS belong
 *       in the core's own console, per the standing back-office rule.</li>
 * </ul>
 */
public interface CoreOperatorPort {

    OperatorAccountView authorizeAndDescribeAccount(OperatorCredential credential, String savingsAccountId);
}
