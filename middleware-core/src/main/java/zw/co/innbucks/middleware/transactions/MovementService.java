package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.config.CoreBankingExceptionHandler;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerOutcome;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.ledger.LedgeredMovementExecutor;
import zw.co.innbucks.middleware.ledger.PendingReservationGuard;
import zw.co.innbucks.middleware.limits.PaymentLimitService;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;
import zw.co.innbucks.middleware.stepup.StepUpRequiredException;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.MovementRequests.DepositRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Customer money movement. The full guard stack, in order:
 *
 * <ol>
 *   <li><b>Cell switch (deposit only):</b> deposits are an unfunded cash-in
 *       and are refused outright unless the cell opts in
 *       ({@link DepositGate}) — before anything is read, asked or written,
 *       and before the request budget below: a refusal that costs nothing
 *       must not spend the customer's {@code /transactions/*} budget, or an
 *       app that still calls deposit on a switched-off cell would eat into
 *       the customer's withdrawals and transfers.</li>
 *   <li><b>Request rate:</b> a per-customer budget on {@code /transactions/*}
 *       (429 {@code rate_limited}), first after the deposit switch — refused
 *       attempts are free, so the count limit below is not a rate limit.</li>
 *   <li><b>Tier:</b> resolved ONCE ({@link KycTierResolver}) and threaded into
 *       step-up and both limit checks; never re-read, never from the token.</li>
 *   <li><b>Limits, advisory (withdraw/transfer):</b> the per-tier payment
 *       limits, checked BEFORE the step-up presence check so a payment that
 *       will be refused gets 422 {@code limit_exceeded}, never
 *       {@code step_up_required}. Skips a key the ledger already holds.</li>
 *   <li><b>Step-up presence (withdraw/transfer):</b> at or above the caller's
 *       KYC-tier threshold, a movement with NO approval token is refused 403
 *       {@code step_up_required} from local data alone — before any core
 *       call, because the app sends every high-value movement once without a
 *       token to collect the fingerprint (PR #53; pinned by
 *       {@code StepUpFlowIntegrationTest.theRefusedFirstAttemptCostsNoCoreCallAtAll}).
 *       <b>Accepted trade-off:</b> a customer whose source account is blocked
 *       learns it only on the token-bearing retry, after one SMS approval
 *       (bounded by the per-customer step-up allowance). Checking the block
 *       first would cost every high-value payment a second account listing to
 *       spare a rare blocked customer one SMS; a full block also signs the
 *       customer out at the next refresh, which leaves debit-only blocks as
 *       the case that pays it.</li>
 *   <li><b>Both local refusals come before the bank block, on purpose.</b>
 *       The block is only known from the ownership listing — the first core
 *       call on this path — so a blocked customer whose payment is ALSO over a
 *       limit is answered {@code limit_exceeded}, not
 *       {@code account_restricted}. Reading the block first would put a core
 *       call ahead of both local checks (the limits must precede the step-up
 *       presence check, and the block would have to precede the limits):
 *       every limit refusal and every token-less high-value first attempt
 *       would then pay the listing. The limit answer is still true — the
 *       payment IS over the limit, block or no block — and costs no SMS and
 *       no state; the next attempt within the limits meets the listing and is
 *       told {@code account_restricted}. Pinned by
 *       {@code MovementServiceTest.aBlockedSourceOverALimitIsToldTheLimitWithoutACoreCall}.</li>
 *   <li><b>Ownership:</b> the authenticated customer must OWN the source
 *       account (withdraw/transfer) or target account (deposit) — checked
 *       against the core's own account list BEFORE anything is claimed or
 *       written. The middleware's write credential can move anyone's money;
 *       this check is the containment.</li>
 *   <li><b>Bank block (withdraw/transfer):</b> the same listing reports the
 *       source account's restriction; a full or debit block is refused here,
 *       403 {@code account_restricted}, with no ledger row and no core write —
 *       unless the key already has a ledger row, whose recorded outcome stays
 *       the answer. The core's own refusal stays the backstop for a block the
 *       listing has not caught up with.</li>
 *   <li><b>Step-up approval (withdraw/transfer):</b> the token is verified and
 *       consumed — a fresh OTP-backed approval bound to exactly this
 *       transaction ({@link StepUpService}) — AFTER ownership and the block
 *       (neither burns it) and BEFORE the idempotency claim, so a refused
 *       movement leaves no state.</li>
 *   <li><b>Idempotency:</b> the caller's key is namespaced per customer
 *       (nobody can collide with or replay another customer's key) and
 *       claim-locked — concurrent duplicates can never double-execute.</li>
 *   <li><b>Ledger + limits, binding:</b> every movement runs through
 *       {@link LedgeredMovementExecutor} — write-ahead row, honest outcome
 *       recording, UNKNOWN parked for reconciliation and rendered as
 *       PROCESSING. For withdraw/transfer the PENDING row is opened only if
 *       the limits still hold, under a per-customer lock, in the same
 *       transaction: the row IS the reservation.</li>
 *   <li><b>Crash-retry:</b> if a ledger row already exists for this key, its
 *       recorded outcome IS the answer (the core dedups the same key
 *       upstream; re-driving a FAILED key would just replay the same
 *       failure). A fresh attempt needs a fresh key.</li>
 * </ol>
 */
@Slf4j
@Service
public class MovementService {

    /**
     * What a same-key replay says for a movement that FAILED for a reason of
     * OURS — the core unreachable or erroring, or refusing our credential or a
     * permission ({@link LedgeredMovementExecutor#isOperationalFailure}): the
     * first answer was a generic 503/502, and the row's failure_message is the
     * adapter's ops detail, which can quote the core's reply. Neutral on
     * purpose: "could not be reached" would be false for a permission refusal.
     */
    static final String NOT_APPLIED_CORE_UNAVAILABLE =
            "The movement did not apply. Use a new Idempotency-Key for a fresh attempt.";


    private final CustomerRepository customerRepository;
    private final CoreBankingPort corePort;
    private final IdempotencyService idempotencyService;
    private final LedgeredMovementExecutor executor;
    private final LedgerTransactionRepository ledgerRepository;
    private final StepUpService stepUpService;
    private final KycTierResolver tierResolver;
    private final PaymentLimitService limitService;
    private final RateLimiterService rateLimiter;
    private final DepositGate depositGate;
    private final Counter withdrawalsRefusedRestricted;
    private final Counter transfersRefusedRestricted;

    public MovementService(CustomerRepository customerRepository,
                           CoreBankingPort corePort,
                           IdempotencyService idempotencyService,
                           LedgeredMovementExecutor executor,
                           LedgerTransactionRepository ledgerRepository,
                           StepUpService stepUpService,
                           KycTierResolver tierResolver,
                           PaymentLimitService limitService,
                           RateLimiterService rateLimiter,
                           DepositGate depositGate,
                           MeterRegistry meterRegistry) {
        this.customerRepository = customerRepository;
        this.corePort = corePort;
        this.idempotencyService = idempotencyService;
        this.executor = executor;
        this.ledgerRepository = ledgerRepository;
        this.stepUpService = stepUpService;
        this.tierResolver = tierResolver;
        this.limitService = limitService;
        this.rateLimiter = rateLimiter;
        this.depositGate = depositGate;
        // An early refusal leaves no ledger row (that is the point), so the
        // "which failures were blocked accounts" question the ledger's
        // account_restricted code answers for core refusals needs this.
        this.withdrawalsRefusedRestricted = restrictedRefusals(meterRegistry, LedgerTransactionType.WITHDRAWAL);
        this.transfersRefusedRestricted = restrictedRefusals(meterRegistry, LedgerTransactionType.TRANSFER);
    }

    private static Counter restrictedRefusals(MeterRegistry registry, LedgerTransactionType type) {
        return Counter.builder("innbucks.transactions.restricted_refusals")
                .description("Debits refused before any write because the core lists the source account as blocked")
                .tag("type", type.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    public IdempotencyService.Result<TransactionResponse> deposit(UUID customerId, String rawKey,
                                                                  DepositRequest request) {
        // First, ahead of every read AND the request budget: a switched-off
        // cell must not spend a database read, a core round trip or the
        // customer's /transactions/* budget on a request it always refuses,
        // and must leave no idempotency claim or ledger row behind.
        depositGate.requireEnabled(customerId);
        // A blank key is the caller's bug, refused (400 missing_header) before
        // the payment budget is spent or the core asked about ownership: a
        // client stuck sending one must not use up its own next real payment.
        IdempotencyKeys.requirePresent(rawKey);
        rateLimiter.enforceCustomerPayment(customerId);
        Customer customer = requireMappedCustomer(customerId);
        requireOwnership(customer, request.accountId());
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        return idempotencyService.execute(key, "POST", "/transactions/deposit", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.DEPOSIT,
                                null, request.accountId(),
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        // Deposits are not limited: no reservation check.
                        PendingReservationGuard.NONE,
                        draftKey -> corePort.deposit(new MoneyMovementCommand(
                                        new AccountRef(request.accountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    public IdempotencyService.Result<TransactionResponse> withdraw(UUID customerId, String rawKey,
                                                                   WithdrawRequest request, String stepUpToken) {
        IdempotencyKeys.requirePresent(rawKey);  // before the budget — see deposit()
        rateLimiter.enforceCustomerPayment(customerId);
        Customer customer = requireMappedCustomer(customerId);
        KycTierResolution tier = tierResolver.resolve(customer);
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        // Limits BEFORE step-up: a payment the limits will refuse must never be
        // answered step_up_required (the customer would fetch an SMS code for
        // nothing). Local and lock-free, like the step-up presence check.
        limitService.precheck(customerId, tier, LedgerTransactionType.WITHDRAWAL,
                request.amountMinor(), key);
        // Purely local, so it runs BEFORE the core round trip that ownership
        // costs: the app's flow sends every high-value movement once WITHOUT a
        // token to collect the fingerprint, and that attempt was always going
        // to be refused. Presence only — the token is verified and consumed by
        // enforce() below, after ownership. The price, accepted (class
        // javadoc): a blocked source above the threshold is told so only on
        // the token-bearing retry, after one SMS approval.
        requireApprovalToken(customerId, tier, LedgerTransactionType.WITHDRAWAL,
                request.accountId(), null, request.amountMinor(), request.currency(), stepUpToken);
        // The first core call: ownership, and the bank's block from the same
        // listing — after both local refusals above (class javadoc says why),
        // before the token is spent and before the claim.
        requireDebitableSource(customer, request.accountId(), key, withdrawalsRefusedRestricted);
        stepUpService.enforce(customerId, tier.tier(), LedgerTransactionType.WITHDRAWAL,
                request.accountId(), null, request.amountMinor(), request.currency(), stepUpToken);
        return idempotencyService.execute(key, "POST", "/transactions/withdraw", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.WITHDRAWAL,
                                request.accountId(), null,
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        limitService.reservationGuard(tier),
                        draftKey -> corePort.withdraw(new MoneyMovementCommand(
                                        new AccountRef(request.accountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    public IdempotencyService.Result<TransactionResponse> transfer(UUID customerId, String rawKey,
                                                                   TransferRequest request, String stepUpToken) {
        IdempotencyKeys.requirePresent(rawKey);  // before the budget — see deposit()
        rateLimiter.enforceCustomerPayment(customerId);
        Customer customer = requireMappedCustomer(customerId);
        KycTierResolution tier = tierResolver.resolve(customer);
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        // Limits before step-up — see withdraw(). A transfer to one of the
        // caller's own accounts counts like any other: only the wallet is
        // app-visible today, and counting is the conservative reading.
        limitService.precheck(customerId, tier, LedgerTransactionType.TRANSFER,
                request.amountMinor(), key);
        // Local pre-check ahead of the core call — see withdraw().
        requireApprovalToken(customerId, tier, LedgerTransactionType.TRANSFER,
                request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                request.currency(), stepUpToken);
        // Only the SOURCE must be the caller's — the destination may be any
        // account in the cell (that's what a transfer is).
        requireDebitableSource(customer, request.fromAccountId(), key, transfersRefusedRestricted);
        stepUpService.enforce(customerId, tier.tier(), LedgerTransactionType.TRANSFER,
                request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                request.currency(), stepUpToken);
        return idempotencyService.execute(key, "POST", "/transactions/transfer", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.TRANSFER,
                                request.fromAccountId(), request.toAccountId(),
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        limitService.reservationGuard(tier),
                        draftKey -> corePort.transfer(new TransferCommand(
                                        new AccountRef(request.fromAccountId()),
                                        new AccountRef(request.toAccountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    /**
     * The step-up presence check, on the request's one resolved tier. On a
     * tier that could not be read, "you need SMS approval" would rest on a
     * guess (BASIC's threshold), so that refusal becomes 503
     * {@code kyc_tier_unavailable}; a movement BASIC would let through still
     * goes through.
     */
    private void requireApprovalToken(UUID customerId, KycTierResolution tier, LedgerTransactionType type,
                                      String sourceAccountId, String targetAccountId,
                                      long amountMinor, String currency, String stepUpToken) {
        try {
            stepUpService.requireApprovalToken(customerId, tier.tier(), type, sourceAccountId,
                    targetAccountId, amountMinor, currency, stepUpToken);
        } catch (StepUpRequiredException ex) {
            if (!tier.authoritative()) {
                throw new KycTierUnavailableException();
            }
            throw ex;
        }
    }

    private TransactionResponse run(String key, LedgerDraft draft, PendingReservationGuard reservationGuard,
                                    Function<TxRef,
                                            zw.co.innbucks.middleware.corebanking.value.TransactionResult> coreCall) {
        // Crash-retry: a ledger row for this key means the movement already ran
        // (or is parked). Its recorded state is the answer — never a second row,
        // never a re-drive (the core would replay the same result anyway). It
        // is also why a replay is counted once: the binding limit check below
        // is only reached when no row exists for the key.
        Optional<LedgerTransaction> existing = ledgerRepository.findByExternalRef(key);
        if (existing.isPresent()) {
            return fromExistingRow(existing.get());
        }
        LedgerOutcome outcome = executor.execute(draft, reservationGuard, coreCall);
        return switch (outcome.status()) {
            case COMPLETED -> TransactionResponse.success(outcome.transactionId(), outcome.coreTxRef());
            case FAILED -> TransactionResponse.failed(outcome.transactionId(),
                    "core_reported_failed", "The core banking system reported the movement did not apply.");
            default -> TransactionResponse.processing(outcome.transactionId(), outcome.coreTxRef());
        };
    }

    private TransactionResponse fromExistingRow(LedgerTransaction row) {
        LedgerStatus status = row.statusEnum();
        log.info("Movement retry resolved from ledger row id={} externalRef={} status={}",
                row.getId(), row.getExternalRef(), status);
        return switch (status) {
            case COMPLETED -> TransactionResponse.success(row.getId(), row.getCoreTxRef());
            // The stored detail is shown only for a refusal of the request
            // itself. For our own failures (credentials or permissions refused,
            // core down) it is operator detail, which the first answer kept in
            // the logs behind a generic 502/503, so the replay does too.
            case FAILED -> TransactionResponse.failed(row.getId(),
                    row.getFailureCode() == null ? "core_reported_failed" : row.getFailureCode(),
                    replayedFailureMessage(row));
            default -> TransactionResponse.processing(row.getId(), row.getCoreTxRef());
        };
    }

    static String replayedFailureMessage(LedgerTransaction row) {
        // A restriction replays the SAME fixed copy its first 403 carried: the
        // row's failure_message is ops detail (the core's code) that the first
        // answer withheld, so a same-key retry must not hand it out either.
        if (CoreBankingExceptionHandler.ACCOUNT_RESTRICTED.equals(row.getFailureCode())) {
            return CoreBankingExceptionHandler.ACCOUNT_RESTRICTED_DETAIL;
        }
        // Same rule for an unreachable core or a refused credential: the first
        // answer was generic, and the stored message is the adapter's detail
        // (on a transfer it can quote the core's reply about another account).
        if (LedgeredMovementExecutor.isOperationalFailure(row.getFailureCode())) {
            return NOT_APPLIED_CORE_UNAVAILABLE;
        }
        // core_rejected: the first 422's own detail. The adapter words every
        // TRANSFER refusal as fixed copy at the source, so this repeats
        // nothing of the core's; deposit/withdraw refusals concern the
        // caller's own account and keep the core's wording.
        return row.getFailureMessage() == null
                ? "The movement did not apply. Use a new Idempotency-Key for a fresh attempt."
                : row.getFailureMessage();
    }

    private Customer requireMappedCustomer(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        if (customer.getCoreExternalId() == null) {
            throw new ProfileNotFoundException(customerId);
        }
        return customer;
    }

    /**
     * Ownership by the CORE's account list — the source of truth, not a local
     * cache. Returns the owned account as listed, restriction included.
     */
    private DepositAccountRef requireOwnership(Customer customer, String accountId) {
        // Refs, not balances: this only asks WHICH accounts are the
        // customer's. Fetching every balance to answer that cost 1+N core
        // calls per movement, all of it discarded.
        List<DepositAccountRef> owned = corePort
                .listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()));
        return owned.stream()
                .filter(a -> a.account().externalId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Ownership check refused account={} for customer={}", accountId, customer.getId());
                    return new AccountOwnershipException(accountId);
                });
    }

    /**
     * Ownership, then the bank's block on the account about to be DEBITED —
     * from the same listing, so no second core call. A full or debit block is
     * refused before the step-up token is spent, before the idempotency claim
     * and before any ledger row: the core would refuse the write anyway, and
     * refusing here leaves nothing to reconcile. Same 403
     * {@code account_restricted} and fixed copy as the core's own refusal
     * ({@link CoreAccountRestrictedException}).
     *
     * <p>Skipped when this key already has a ledger row: a same-key retry is
     * asking what happened to a movement that ran BEFORE the block, and its
     * recorded outcome — a completed payment included — is the only true
     * answer. Telling it "account restricted" would read as "your payment
     * failed" for one that may have succeeded.
     */
    private void requireDebitableSource(Customer customer, String accountId, String key, Counter refusals) {
        DepositAccountRef source = requireOwnership(customer, accountId);
        if (!source.restriction().blocksDebits() || ledgerRepository.findByExternalRef(key).isPresent()) {
            return;
        }
        refusals.increment();
        log.info("Refused a debit from account={} for customer={}: the core lists it {} (no ledger row, no core write)",
                accountId, customer.getId(), source.restriction());
        throw new CoreAccountRestrictedException(corePort.provider(),
                "The core lists the source account " + source.restriction() + "; refused before any write", null);
    }
}
