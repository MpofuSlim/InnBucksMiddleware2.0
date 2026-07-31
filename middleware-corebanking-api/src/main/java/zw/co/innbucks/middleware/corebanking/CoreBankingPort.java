package zw.co.innbucks.middleware.corebanking;

import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.util.List;
import java.util.Set;

/**
 * The single seam between the middleware's domain layer and whichever core
 * banking system a deployment runs against (Fineract now, Veengu later).
 * Orchestration code depends on this interface only — never on an adapter
 * class, and never on {@code instanceof} checks; behavioural differences
 * between cores are expressed through {@link #capabilities()}.
 *
 * <p><b>Contract invariants every adapter MUST uphold:</b>
 *
 * <ul>
 *   <li><b>Money crosses this port in minor units</b> ({@link
 *       zw.co.innbucks.middleware.corebanking.value.MinorUnits}). Each adapter
 *       owns exactly ONE conversion point to its core's wire representation and
 *       MUST cross-check any amount echoed back by the core against the amount
 *       sent (the 100x-charge guard).</li>
 *   <li><b>Writes are NEVER auto-retried.</b> Only reads may retry on
 *       transient failure. A retried write can move money twice; idempotency
 *       keys are the caller's dedup, not an excuse to retry.</li>
 *   <li><b>Ambiguous write outcomes surface as {@link
 *       CoreUnknownOutcomeException}</b> — thrown when the adapter cannot
 *       prove whether the core applied the write (timeout mid-flight,
 *       connection reset after send, core replied "under processing"). The
 *       caller parks the operation for reconciliation via
 *       {@link #getTransaction(TxRef)}; it never guesses, never retries, and
 *       never auto-expires the parked row. Blocked beats double-charged.</li>
 *   <li>Every mutating method takes the caller-supplied {@link IdempotencyKey}
 *       and propagates it to the core when the core supports server-side dedup
 *       (see {@link CoreCapability#SERVER_SIDE_DEDUP}).</li>
 * </ul>
 */
public interface CoreBankingPort {

    CoreProvider provider();

    /** Which optional behaviours this core supports. Branch on these, never on the adapter type. */
    Set<CoreCapability> capabilities();

    /**
     * Create the banking relationship for a middleware customer. With
     * {@link CoreCapability#CLIENT_ASSIGNED_EXTERNAL_ID} the adapter sends
     * {@code cmd.requestedExternalId()} (our customer UUID) and the returned
     * ref echoes it; without it the core assigns the externalId and the caller
     * must persist the returned ref after the call.
     */
    CoreCustomerRef createCustomer(CreateCustomerCommand cmd, IdempotencyKey key);

    /**
     * Open (and fully activate) the customer's wallet/deposit account. The
     * adapter owns whatever multi-step saga its core requires (Fineract:
     * create → approve → activate, each leg idempotency-keyed off {@code key}
     * and individually resumable), and MUST be safe to re-invoke after a
     * partial failure — a retry picks the saga up where it died.
     *
     * @param requestedExternalId the account externalId the middleware mints
     *        (e.g. {@code <customerUuid>:wallet}); adapters for cores without
     *        {@link CoreCapability#CLIENT_ASSIGNED_EXTERNAL_ID} may ignore it
     *        and the caller persists the returned ref.
     */
    AccountRef openDepositAccount(CoreCustomerRef customer, String requestedExternalId, IdempotencyKey key);

    CustomerProfile getProfile(CoreCustomerRef ref);

    List<DepositAccountSummary> listDepositAccounts(CoreCustomerRef ref);

    AccountBalance getBalance(AccountRef account);

    TransactionResult deposit(MoneyMovementCommand cmd, IdempotencyKey key);

    TransactionResult withdraw(MoneyMovementCommand cmd, IdempotencyKey key);

    TransactionResult transfer(TransferCommand cmd, IdempotencyKey key);

    /**
     * Reconciliation read for a previously submitted transaction. This is the
     * ONLY retried path for money movement: after a
     * {@link CoreUnknownOutcomeException} the caller polls this until the core
     * gives a definitive state.
     *
     * <p>Outcome contract: {@code COMPLETED}/{@code FAILED} must be POSITIVE
     * core answers (found-applied, found-failed, or ref-definitively-absent
     * where the adapter always attaches the ref to the original write —
     * absence then proves the write never landed). When the adapter cannot
     * prove either way, return {@code UNKNOWN} — the row stays parked for the
     * operator; never guess.
     */
    TransactionResult getTransaction(TransactionLookup lookup);

    /**
     * One page of the account's statement, newest first, as the CORE recorded
     * it. A READ — retryable, and never a source of truth about our own
     * in-flight movements: a row this middleware has parked as UNKNOWN has by
     * definition no confirmed core entry, so it will not appear here until it
     * reconciles. Callers that need to show pending activity must merge the
     * local ledger themselves.
     */
    TransactionPage listTransactions(TransactionHistoryQuery query);
}
