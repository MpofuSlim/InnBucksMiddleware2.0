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
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerOutcome;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.ledger.LedgeredMovementExecutor;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.MovementRequests.DepositRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Customer money movement. The full guard stack, in order:
 *
 * <ol>
 *   <li><b>Step-up presence (withdraw/transfer):</b> at or above the caller's
 *       KYC-tier threshold, a movement with NO approval token is refused 403
 *       {@code step_up_required} from local data alone — before any core
 *       call, because the app sends every high-value movement once without a
 *       token to collect the fingerprint (PR #53; pinned by
 *       {@code StepUpFlowIntegrationTest.theRefusedFirstAttemptCostsNoCoreCallAtAll}).
 *       <b>Accepted trade-off:</b> a customer whose source account is blocked
 *       learns it only on the token-bearing retry, after one SMS approval
 *       (bounded by the per-number OTP allowance). Checking the block first
 *       would cost every high-value payment a second account listing to spare
 *       a rare blocked customer one SMS; a full block also signs the customer
 *       out at the next refresh, which leaves debit-only blocks as the case
 *       that pays it.</li>
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
 *   <li><b>Ledger:</b> every movement runs through
 *       {@link LedgeredMovementExecutor} — write-ahead row, honest outcome
 *       recording, UNKNOWN parked for reconciliation and rendered as
 *       PROCESSING.</li>
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
     * What a same-key replay says for a movement that FAILED because the core
     * could not be reached or refused our credential: the first answer was a
     * generic 503/502, and the row's failure_message is the adapter's ops
     * detail, which can quote the core's reply.
     */
    static final String NOT_APPLIED_CORE_UNAVAILABLE = "The banking system could not be reached, so the "
            + "movement did not apply. Use a new Idempotency-Key for a fresh attempt.";

    private static final Set<String> CORE_UNAVAILABLE_CODES = Set.of(
            LedgeredMovementExecutor.CORE_AUTH_FAILED,
            LedgeredMovementExecutor.CORE_UNREACHABLE,
            LedgeredMovementExecutor.CORE_SERVER_ERROR);

    private final CustomerRepository customerRepository;
    private final CoreBankingPort corePort;
    private final IdempotencyService idempotencyService;
    private final LedgeredMovementExecutor executor;
    private final LedgerTransactionRepository ledgerRepository;
    private final StepUpService stepUpService;
    private final Counter withdrawalsRefusedRestricted;
    private final Counter transfersRefusedRestricted;

    public MovementService(CustomerRepository customerRepository,
                           CoreBankingPort corePort,
                           IdempotencyService idempotencyService,
                           LedgeredMovementExecutor executor,
                           LedgerTransactionRepository ledgerRepository,
                           StepUpService stepUpService,
                           MeterRegistry meterRegistry) {
        this.customerRepository = customerRepository;
        this.corePort = corePort;
        this.idempotencyService = idempotencyService;
        this.executor = executor;
        this.ledgerRepository = ledgerRepository;
        this.stepUpService = stepUpService;
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
        Customer customer = requireMappedCustomer(customerId);
        requireOwnership(customer, request.accountId());
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        return idempotencyService.execute(key, "POST", "/transactions/deposit", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.DEPOSIT,
                                null, request.accountId(),
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        draftKey -> corePort.deposit(new MoneyMovementCommand(
                                        new AccountRef(request.accountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    public IdempotencyService.Result<TransactionResponse> withdraw(UUID customerId, String rawKey,
                                                                   WithdrawRequest request, String stepUpToken) {
        Customer customer = requireMappedCustomer(customerId);
        // Purely local, so it runs BEFORE the core round trip that ownership
        // costs: the app's flow sends every high-value movement once WITHOUT a
        // token to collect the fingerprint, and that attempt was always going
        // to be refused. Presence only — the token is verified and consumed by
        // enforce() below, after ownership. The price, accepted (class
        // javadoc): a blocked source above the threshold is told so only on
        // the token-bearing retry, after one SMS approval.
        stepUpService.requireApprovalToken(customerId, customer.kycTierEnum(),
                LedgerTransactionType.WITHDRAWAL, request.accountId(), null,
                request.amountMinor(), request.currency(), stepUpToken);
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        requireDebitableSource(customer, request.accountId(), key, withdrawalsRefusedRestricted);
        stepUpService.enforce(customerId, customer.kycTierEnum(), LedgerTransactionType.WITHDRAWAL,
                request.accountId(), null, request.amountMinor(), request.currency(), stepUpToken);
        return idempotencyService.execute(key, "POST", "/transactions/withdraw", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.WITHDRAWAL,
                                request.accountId(), null,
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        draftKey -> corePort.withdraw(new MoneyMovementCommand(
                                        new AccountRef(request.accountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    public IdempotencyService.Result<TransactionResponse> transfer(UUID customerId, String rawKey,
                                                                   TransferRequest request, String stepUpToken) {
        Customer customer = requireMappedCustomer(customerId);
        // Local pre-check ahead of the core call — see withdraw().
        stepUpService.requireApprovalToken(customerId, customer.kycTierEnum(),
                LedgerTransactionType.TRANSFER, request.fromAccountId(), request.toAccountId(),
                request.amountMinor(), request.currency(), stepUpToken);
        // Only the SOURCE must be the caller's — the destination may be any
        // account in the cell (that's what a transfer is).
        String key = IdempotencyKeys.namespaced(customerId.toString(), rawKey);
        requireDebitableSource(customer, request.fromAccountId(), key, transfersRefusedRestricted);
        stepUpService.enforce(customerId, customer.kycTierEnum(), LedgerTransactionType.TRANSFER,
                request.fromAccountId(), request.toAccountId(), request.amountMinor(),
                request.currency(), stepUpToken);
        return idempotencyService.execute(key, "POST", "/transactions/transfer", request,
                TransactionResponse.class, () -> run(key, new LedgerDraft(
                                customerId, LedgerTransactionType.TRANSFER,
                                request.fromAccountId(), request.toAccountId(),
                                new MinorUnits(request.amountMinor(), request.currency()),
                                request.narrative(), key, corePort.provider()),
                        draftKey -> corePort.transfer(new TransferCommand(
                                        new AccountRef(request.fromAccountId()),
                                        new AccountRef(request.toAccountId()),
                                        new MinorUnits(request.amountMinor(), request.currency()),
                                        request.narrative(), new TxRef(key)),
                                new IdempotencyKey(key))));
    }

    private TransactionResponse run(String key, LedgerDraft draft,
                                    java.util.function.Function<TxRef,
                                            zw.co.innbucks.middleware.corebanking.value.TransactionResult> coreCall) {
        // Crash-retry: a ledger row for this key means the movement already ran
        // (or is parked). Its recorded state is the answer — never a second row,
        // never a re-drive (the core would replay the same result anyway).
        Optional<LedgerTransaction> existing = ledgerRepository.findByExternalRef(key);
        if (existing.isPresent()) {
            return fromExistingRow(existing.get());
        }
        LedgerOutcome outcome = executor.execute(draft, coreCall);
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
        // (Null-checked first: Set.of(...).contains(null) throws.)
        if (row.getFailureCode() != null && CORE_UNAVAILABLE_CODES.contains(row.getFailureCode())) {
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
