package zw.co.innbucks.middleware.limits;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.ledger.PendingReservationGuard;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.stepup.StepUpService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tier payment limits on app withdrawals and transfers. The same rules run
 * in two layers:
 *
 * <ol>
 *   <li><b>Advisory</b> ({@link #precheck}) — in {@code MovementService} BEFORE
 *       the step-up presence check, with no lock, so a payment that will be
 *       refused is answered {@code limit_exceeded}, never
 *       {@code step_up_required} (nobody is sent to fetch an SMS code for it).
 *       It SKIPS when the ledger already holds a row for this namespaced
 *       Idempotency-Key: a replay must return its recorded outcome, not be
 *       counted against itself. The key is read again before any refusal,
 *       for a duplicate whose original commits in between.</li>
 *   <li><b>Binding</b> ({@link #reservationGuard}) — inside the ONE
 *       transaction that opens the PENDING ledger row: per-customer advisory
 *       lock, sum, then refuse (nothing written) or let the INSERT proceed.
 *       The PENDING row IS the reservation; a later FAILED releases it
 *       because the sum excludes FAILED.</li>
 * </ol>
 *
 * <p>Accepted race: a payment can pass the advisory check, spend its SMS
 * approval, and then lose the binding check to a concurrent payment. The
 * approval is bound to amount and destination, so the customer re-approves
 * any retry anyway; the money side is never wrong.
 *
 * <p>Never computed from the in-memory rate limiter: buckets reset on
 * restart and are per replica. The ledger is the only source of usage.
 */
@Slf4j
@Service
public class PaymentLimitService {

    private final PaymentLimitsPolicy policy;
    private final LimitUsageStore usageStore;
    private final LedgerTransactionRepository ledgerRepository;
    private final CustomerRepository customerRepository;
    private final KycTierResolver tierResolver;
    private final StepUpService stepUpService;
    private final ZoneId zone;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public PaymentLimitService(PaymentLimitsPolicy policy,
                               LimitUsageStore usageStore,
                               LedgerTransactionRepository ledgerRepository,
                               CustomerRepository customerRepository,
                               KycTierResolver tierResolver,
                               StepUpService stepUpService,
                               CountryProperties countryProperties,
                               Clock clock,
                               MeterRegistry meterRegistry) {
        this.policy = policy;
        this.usageStore = usageStore;
        this.ledgerRepository = ledgerRepository;
        this.customerRepository = customerRepository;
        this.tierResolver = tierResolver;
        this.stepUpService = stepUpService;
        this.zone = countryProperties.country().zoneId();
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Advisory check. No lock; may read a slightly stale picture, which is
     * fine because the binding check repeats it under the lock.
     *
     * @param externalRef the namespaced Idempotency-Key (the ledger's external_ref)
     * @throws PaymentLimitExceededException the payment would break a limit
     * @throws KycTierUnavailableException    it would, but the tier could not be read
     */
    public void precheck(UUID customerId, KycTierResolution tier, LedgerTransactionType type,
                         long amountMinor, String externalRef) {
        if (!isLimited(type)) {
            return;
        }
        TierLimits limits = policy.limitsFor(tier.tier());
        if (limits.isDormant()) {
            return;
        }
        if (ledgerRepository.findByExternalRef(externalRef).isPresent()) {
            // A replay (crash-retry, or the retry after an SMS approval of an
            // already-executed payment): its ledger row decides the answer,
            // and that row is already in the sum — counting it again would
            // refuse a payment that already happened.
            log.debug("Limit pre-check skipped: ledger already holds externalRef={}", externalRef);
            return;
        }
        LimitWindows windows = LimitWindows.at(clock.instant(), zone);
        LimitUsage usage = limits.needsUsage()
                ? usageStore.usage(customerId, policy.currency(), windows) : LimitUsage.NONE;
        Optional<LimitRefusal> refusal = evaluate(limits, usage, amountMinor, windows, tier.tier());
        if (refusal.isPresent()) {
            if (ledgerRepository.findByExternalRef(externalRef).isPresent()) {
                // A same-key duplicate (a double-tap) whose original committed
                // its PENDING row between the skip above and the usage query:
                // the sum may be counting that very row. Read AFTER the usage
                // query, so any row the sum saw is visible here. Its outcome
                // (or 409 idempotency_in_progress) is the answer, not a
                // refusal of a payment that is going through. Paid for only on
                // the refusal path, and keeps ONE usage predicate for every
                // check.
                log.debug("Limit pre-check refusal dropped: externalRef={} appeared in the ledger meanwhile",
                        externalRef);
                return;
            }
            refuse(refusal.get(), tier, "advisory", customerId, amountMinor);
        }
    }

    /**
     * The binding check, as a guard for
     * {@code LedgeredMovementExecutor#execute(draft, guard, coreCall)}. Takes the
     * tier resolved ONCE for this request — never re-reads it inside the
     * transaction (a future live core read must not run there).
     */
    public PendingReservationGuard reservationGuard(KycTierResolution tier) {
        return (draft, openedAt) -> enforceWithinReservation(draft, openedAt, tier);
    }

    void enforceWithinReservation(LedgerDraft draft, Instant openedAt, KycTierResolution tier) {
        if (!isLimited(draft.type())) {
            return;
        }
        TierLimits limits = policy.limitsFor(tier.tier());
        if (limits.isDormant()) {
            return;
        }
        LimitWindows windows = LimitWindows.at(openedAt, zone);
        LimitUsage usage = LimitUsage.NONE;
        if (limits.needsUsage()) {
            // Serialise this customer's reservations, then read under READ
            // COMMITTED: the previous holder's PENDING row is committed and
            // visible by the time this statement runs.
            usageStore.lockCustomer(draft.customerId());
            usage = usageStore.usage(draft.customerId(), policy.currency(), windows);
        }
        Optional<LimitRefusal> refusal = evaluate(limits, usage, draft.amount().amount(), windows, tier.tier());
        if (refusal.isPresent()) {
            refuse(refusal.get(), tier, "binding", draft.customerId(), draft.amount().amount());
        }
    }

    /**
     * {@code GET /me/limits}: the same policy, the same usage query, the same
     * windows as a refusal — computed now.
     */
    public LimitsResponse viewFor(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        KycTierResolution tier = tierResolver.resolve(customer);
        if (!tier.authoritative()) {
            // Showing basic's limits for a customer whose tier is unknown would
            // be the same confident falsehood a refusal would be.
            throw new KycTierUnavailableException();
        }
        TierLimits limits = policy.limitsFor(tier.tier());
        LimitWindows windows = LimitWindows.at(clock.instant(), zone);
        LimitUsage usage = limits.needsUsage()
                ? usageStore.usage(customerId, policy.currency(), windows) : LimitUsage.NONE;
        return assemble(tier.tier(), policy.currency(), stepUpService.thresholdAtOrAbove(tier.tier()),
                limits, usage, windows);
    }

    // --- Pure rules (unit-tested directly) ---------------------------------

    /**
     * Would a payment of {@code amountMinor} break a limit? Money limits refuse
     * when {@code used + amount > limit} (exactly reaching a limit is allowed);
     * the count refuses when today's payments already number {@code limit}.
     *
     * <p>When several are broken, ONE is reported, chosen to be the most
     * actionable:
     * <ol>
     *   <li>per_payment — no waiting fixes it;</li>
     *   <li>daily_count — nothing at all can be sent until midnight;</li>
     *   <li>of daily and monthly, the one with LESS left (it is the real
     *       ceiling); on a tie, monthly, whose reset is the truthful one.</li>
     * </ol>
     *
     * <p>{@code remainingMinor} of every MONEY refusal is the largest payment
     * that would pass now — exactly {@link #sendableNow}, i.e. what
     * {@code GET /me/limits} shows as {@code sendableNowMinor}. For daily and
     * monthly that falls out of the precedence above (the reported period has
     * the smallest remainder). For per_payment it is set explicitly: the cap
     * itself never depletes, but offering it when the day or month has less
     * left would send the customer straight into a second refusal.
     */
    static Optional<LimitRefusal> evaluate(TierLimits limits, LimitUsage usage, long amountMinor,
                                           LimitWindows windows, KycTier tier) {
        Long perPayment = limits.perPaymentMinor();
        if (perPayment != null && amountMinor > perPayment) {
            return Optional.of(LimitRefusal.money(LimitKind.PER_PAYMENT, perPayment,
                    sendableNow(limits, usage), null, tier));
        }
        Integer count = limits.dailyCount();
        if (count != null && usage.dayCount() >= count) {
            return Optional.of(LimitRefusal.count(count, Math.max(0L, count - usage.dayCount()),
                    windows.dayResetsAt(), tier));
        }
        Long dailyLeft = remaining(limits.dailyMinor(), usage.dayUsedMinor());
        Long monthlyLeft = remaining(limits.monthlyMinor(), usage.monthUsedMinor());
        boolean dailyOver = dailyLeft != null && amountMinor > dailyLeft;
        boolean monthlyOver = monthlyLeft != null && amountMinor > monthlyLeft;
        if (monthlyOver && (!dailyOver || monthlyLeft <= dailyLeft)) {
            return Optional.of(LimitRefusal.money(LimitKind.MONTHLY, limits.monthlyMinor(), monthlyLeft,
                    windows.monthResetsAt(), tier));
        }
        if (dailyOver) {
            return Optional.of(LimitRefusal.money(LimitKind.DAILY, limits.dailyMinor(), dailyLeft,
                    windows.dayResetsAt(), tier));
        }
        return Optional.empty();
    }

    /**
     * The largest single payment that passes every configured limit now:
     * min(per_payment, daily remainder, monthly remainder), 0 when the daily
     * count is used up, absent kinds ignored. {@code null} when the tier has no
     * money limit at all (and its count is not used up) — there is no cap, and
     * inventing a number would be a lie the app would render.
     */
    static Long sendableNow(TierLimits limits, LimitUsage usage) {
        if (limits.dailyCount() != null && usage.dayCount() >= limits.dailyCount()) {
            return 0L;
        }
        Long best = null;
        for (Long candidate : new Long[] {
                limits.perPaymentMinor(),
                remaining(limits.dailyMinor(), usage.dayUsedMinor()),
                remaining(limits.monthlyMinor(), usage.monthUsedMinor())}) {
            if (candidate != null && (best == null || candidate < best)) {
                best = candidate;
            }
        }
        return best;
    }

    static LimitsResponse assemble(KycTier tier, String currency, Long stepUpAtOrAbove,
                                   TierLimits limits, LimitUsage usage, LimitWindows windows) {
        List<LimitsResponse.LimitView> views = new ArrayList<>();
        if (limits.perPaymentMinor() != null) {
            views.add(LimitsResponse.LimitView.perPayment(limits.perPaymentMinor()));
        }
        if (limits.dailyMinor() != null) {
            views.add(LimitsResponse.LimitView.money(LimitKind.DAILY, limits.dailyMinor(),
                    usage.dayUsedMinor(), usage.dayProcessingMinor(), windows.dayResetsAt()));
        }
        if (limits.monthlyMinor() != null) {
            views.add(LimitsResponse.LimitView.money(LimitKind.MONTHLY, limits.monthlyMinor(),
                    usage.monthUsedMinor(), usage.monthProcessingMinor(), windows.monthResetsAt()));
        }
        if (limits.dailyCount() != null) {
            views.add(LimitsResponse.LimitView.count(limits.dailyCount(), usage.dayCount(),
                    windows.dayResetsAt()));
        }
        return new LimitsResponse(tier.dbValue(), currency, stepUpAtOrAbove,
                sendableNow(limits, usage), List.copyOf(views));
    }

    private static Long remaining(Long limit, long used) {
        return limit == null ? null : Math.max(0L, limit - used);
    }

    /** Only money going OUT through the app is limited here. */
    private static boolean isLimited(LedgerTransactionType type) {
        return type == LedgerTransactionType.WITHDRAWAL || type == LedgerTransactionType.TRANSFER;
    }

    private void refuse(LimitRefusal refusal, KycTierResolution tier, String check,
                        UUID customerId, long amountMinor) {
        if (!tier.authoritative()) {
            // The refusal rests on BASIC-by-default, not on the customer's real
            // tier: never tell them a limit they may not have. 503 = retry.
            counter(refusal.kind(), check, "tier_unavailable").increment();
            log.warn("Limit check would refuse customer={} on an UNREADABLE tier ({} {}); answering 503",
                    customerId, check, refusal.kind().wireName());
            throw new KycTierUnavailableException();
        }
        counter(refusal.kind(), check, "refused").increment();
        log.info("Payment limit refused customer={} tier={} limit={} check={} amountMinor={} remaining={}",
                customerId, refusal.kycTier().dbValue(), refusal.kind().wireName(), check, amountMinor,
                refusal.kind().isMoney() ? refusal.remainingMinor() : refusal.remainingCount());
        throw new PaymentLimitExceededException(refusal);
    }

    private Counter counter(LimitKind kind, String check, String outcome) {
        return Counter.builder("innbucks.payment_limits.refused")
                .description("Withdrawals/transfers refused by a per-tier limit. check=advisory is the "
                        + "pre-step-up read; check=binding is the locked check inside the ledger transaction "
                        + "(non-zero binding = concurrent payments raced for the same allowance)")
                .tag("limit", kind.wireName())
                .tag("check", check)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
