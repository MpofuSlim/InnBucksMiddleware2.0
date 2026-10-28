package zw.co.innbucks.middleware.transactions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
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
import zw.co.innbucks.middleware.stepup.StepUpRequiredException;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.MovementRequests.DepositRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Customer money movement. The full guard stack, in order:
 *
 * <ol>
 *   <li><b>Tier:</b> resolved ONCE ({@link KycTierResolver}) and threaded into
 *       step-up and both limit checks; never re-read, never from the token.</li>
 *   <li><b>Limits, advisory (withdraw/transfer):</b> the per-tier payment
 *       limits, checked BEFORE the step-up presence check so a payment that
 *       will be refused gets 422 {@code limit_exceeded}, never
 *       {@code step_up_required}. Skips a key the ledger already holds.</li>
 *   <li><b>Ownership:</b> the authenticated customer must OWN the source
 *       account (withdraw/transfer) or target account (deposit) — checked
 *       against the core's own account list BEFORE anything is claimed or
 *       written. The middleware's write credential can move anyone's money;
 *       this check is the containment.</li>
 *   <li><b>Step-up (withdraw/transfer):</b> at or above the caller's KYC-tier
 *       threshold, the movement needs a fresh OTP-backed approval bound to
 *       exactly this transaction ({@link StepUpService}) — checked BEFORE the
 *       idempotency claim so a refused movement leaves no state.</li>
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
@RequiredArgsConstructor
public class MovementService {

    private final CustomerRepository customerRepository;
    private final CoreBankingPort corePort;
    private final IdempotencyService idempotencyService;
    private final LedgeredMovementExecutor executor;
    private final LedgerTransactionRepository ledgerRepository;
    private final StepUpService stepUpService;
    private final KycTierResolver tierResolver;
    private final PaymentLimitService limitService;

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
        // enforce() below, after ownership.
        requireApprovalToken(customerId, tier, LedgerTransactionType.WITHDRAWAL,
                request.accountId(), null, request.amountMinor(), request.currency(), stepUpToken);
        requireOwnership(customer, request.accountId());
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
        requireOwnership(customer, request.fromAccountId());
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
            case FAILED -> TransactionResponse.failed(row.getId(),
                    row.getFailureCode() == null ? "core_reported_failed" : row.getFailureCode(),
                    row.getFailureMessage() == null
                            ? "The movement did not apply. Use a new Idempotency-Key for a fresh attempt."
                            : row.getFailureMessage());
            default -> TransactionResponse.processing(row.getId(), row.getCoreTxRef());
        };
    }

    private Customer requireMappedCustomer(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        if (customer.getCoreExternalId() == null) {
            throw new ProfileNotFoundException(customerId);
        }
        return customer;
    }

    /** Ownership by the CORE's account list — the source of truth, not a local cache. */
    private void requireOwnership(Customer customer, String accountId) {
        // Refs, not balances: this only asks WHICH accounts are the
        // customer's. Fetching every balance to answer that cost 1+N core
        // calls per movement, all of it discarded.
        List<String> owned = corePort
                .listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()))
                .stream().map(a -> a.account().externalId()).toList();
        if (!owned.contains(accountId)) {
            log.warn("Ownership check refused account={} for customer={}", accountId, customer.getId());
            throw new AccountOwnershipException(accountId);
        }
    }
}
