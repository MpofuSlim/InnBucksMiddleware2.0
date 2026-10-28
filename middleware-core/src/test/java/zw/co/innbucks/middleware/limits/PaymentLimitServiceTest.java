package zw.co.innbucks.middleware.limits;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRecordKycTierResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.stepup.StepUpService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The limit rules and both enforcement layers, without a database. The usage
 * query itself is pinned by {@code LimitUsageStoreTest} and, against real
 * Postgres, by the app module's {@code PaymentLimitsIntegrationTest}.
 */
class PaymentLimitServiceTest {

    private static final UUID CUSTOMER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");
    private static final String KEY = "a".repeat(64);
    /** 10:00 Harare on 28 Oct 2026. */
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");

    private static final TierLimits BASIC = new TierLimits(100000L, 150000L, 500000L, 10);
    private static final TierLimits STANDARD = new TierLimits(200000L, 400000L, 1500000L, 20);
    private static final TierLimits ENHANCED = new TierLimits(1000000L, 2000000L, 10000000L, null);

    private final LimitUsageStore usageStore = mock(LimitUsageStore.class);
    private final LedgerTransactionRepository ledger = mock(LedgerTransactionRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final StepUpService stepUp = mock(StepUpService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LimitWindows windows = LimitWindows.at(NOW, Country.ZW.zoneId());

    private PaymentLimitService service;

    private static PaymentLimitsPolicy policy(TierLimits basic, TierLimits standard, TierLimits enhanced) {
        return new PaymentLimitsPolicy(Country.ZW, "USD", "limits/limits-zw.yaml", "0".repeat(64),
                Map.of(KycTier.BASIC, basic, KycTier.STANDARD, standard, KycTier.ENHANCED, enhanced),
                List.of());
    }

    private PaymentLimitService service(PaymentLimitsPolicy policy) {
        return new PaymentLimitService(policy, usageStore, ledger, customers,
                new CustomerRecordKycTierResolver(meters), stepUp, new CountryProperties(Country.ZW),
                Clock.fixed(NOW, ZoneOffset.UTC), meters);
    }

    @BeforeEach
    void setUp() {
        service = service(policy(BASIC, STANDARD, ENHANCED));
        when(ledger.findByExternalRef(any())).thenReturn(Optional.empty());
    }

    private static LimitUsage used(long dayMinor, long monthMinor, long dayCount) {
        return new LimitUsage(dayMinor, 0, dayCount, monthMinor, 0);
    }

    // --- evaluate: which limit, and the numbers ---------------------------

    @Test
    void reachingALimitExactlyIsAllowedOneMoreMinorUnitIsNot() {
        LimitUsage usage = used(50000, 50000, 1);
        assertThat(PaymentLimitService.evaluate(BASIC, usage, 100000, windows, KycTier.BASIC)).isEmpty();

        LimitRefusal refusal = PaymentLimitService.evaluate(BASIC, used(50001, 50001, 1), 100000,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(refusal.kind()).isEqualTo(LimitKind.DAILY);
        assertThat(refusal.limitMinor()).isEqualTo(150000L);
        assertThat(refusal.remainingMinor()).isEqualTo(99999L);
        assertThat(refusal.resetsAt()).isEqualTo("2026-10-29T00:00:00+02:00");
        assertThat(refusal.kycTier()).isEqualTo(KycTier.BASIC);
        assertThat(refusal.nextTier()).isEqualTo(KycTier.STANDARD);
    }

    @Test
    void perPaymentIsReportedFirstAndNeverResets() {
        LimitRefusal refusal = PaymentLimitService.evaluate(BASIC, used(149000, 499000, 10), 100001,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(refusal.kind()).isEqualTo(LimitKind.PER_PAYMENT);
        assertThat(refusal.limitMinor()).isEqualTo(100000L);
        assertThat(refusal.remainingMinor()).as("a per-payment allowance never depletes").isEqualTo(100000L);
        assertThat(refusal.resetsAt()).isNull();
    }

    @Test
    void anExhaustedDailyCountRefusesAnyAmount() {
        LimitRefusal refusal = PaymentLimitService.evaluate(BASIC, used(1000, 1000, 10), 1,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(refusal.kind()).isEqualTo(LimitKind.DAILY_COUNT);
        assertThat(refusal.limitCount()).isEqualTo(10L);
        assertThat(refusal.remainingCount()).isZero();
        assertThat(refusal.limitMinor()).isNull();
        assertThat(refusal.resetsAt()).isEqualTo("2026-10-29T00:00:00+02:00");
        // The 10th payment of the day is still allowed.
        assertThat(PaymentLimitService.evaluate(BASIC, used(1000, 1000, 9), 1, windows, KycTier.BASIC)).isEmpty();
    }

    @Test
    void monthlyIsReportedWithTheMonthsReset() {
        LimitRefusal refusal = PaymentLimitService.evaluate(BASIC, used(0, 450000, 0), 60000,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(refusal.kind()).isEqualTo(LimitKind.MONTHLY);
        assertThat(refusal.remainingMinor()).isEqualTo(50000L);
        assertThat(refusal.resetsAt()).isEqualTo("2026-11-01T00:00:00+02:00");
    }

    @Test
    void whenDailyAndMonthlyAreBothBrokenTheSmallerRemainderIsReported() {
        // Daily has 20,000 left, monthly 30,000: daily is the real ceiling.
        LimitRefusal daily = PaymentLimitService.evaluate(BASIC, used(130000, 470000, 1), 40000,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(daily.kind()).isEqualTo(LimitKind.DAILY);
        assertThat(daily.remainingMinor()).isEqualTo(20000L);

        // Monthly has less left: monthly.
        LimitRefusal monthly = PaymentLimitService.evaluate(BASIC, used(100000, 490000, 1), 40000,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(monthly.kind()).isEqualTo(LimitKind.MONTHLY);
        assertThat(monthly.remainingMinor()).isEqualTo(10000L);

        // A tie goes to monthly, whose reset is the truthful one.
        LimitRefusal tie = PaymentLimitService.evaluate(BASIC, used(140000, 490000, 1), 40000,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(tie.kind()).isEqualTo(LimitKind.MONTHLY);
    }

    @Test
    void usageAboveATightenedLimitLeavesZeroNotANegativeRemainder() {
        LimitRefusal refusal = PaymentLimitService.evaluate(BASIC, used(170000, 170000, 1), 1,
                windows, KycTier.BASIC).orElseThrow();
        assertThat(refusal.remainingMinor()).isZero();
    }

    @Test
    void absentKindsAreUnlimitedAndTheTopTierHasNoNextTier() {
        LimitRefusal refusal = PaymentLimitService.evaluate(ENHANCED, used(0, 0, 500), 1000001,
                windows, KycTier.ENHANCED).orElseThrow();
        assertThat(refusal.kind()).as("no daily_count on enhanced: 500 payments today is fine")
                .isEqualTo(LimitKind.PER_PAYMENT);
        assertThat(refusal.nextTier()).isNull();
        assertThat(PaymentLimitService.evaluate(TierLimits.none(), used(9_999_999, 9_999_999, 999),
                Long.MAX_VALUE, windows, KycTier.BASIC)).isEmpty();
    }

    // --- sendableNowMinor -----------------------------------------------

    @Test
    void sendableNowIsTheSmallestConfiguredHeadroom() {
        assertThat(PaymentLimitService.sendableNow(BASIC, used(12500, 81000, 3))).isEqualTo(100000L);
        assertThat(PaymentLimitService.sendableNow(BASIC, used(120000, 81000, 3))).isEqualTo(30000L);
        assertThat(PaymentLimitService.sendableNow(BASIC, used(0, 480000, 3))).isEqualTo(20000L);
    }

    @Test
    void sendableNowIsZeroWhenTheCountIsUsedUpEvenWithMoneyLeft() {
        assertThat(PaymentLimitService.sendableNow(BASIC, used(0, 0, 10))).isZero();
        assertThat(PaymentLimitService.sendableNow(new TierLimits(null, null, null, 2), used(0, 0, 2))).isZero();
    }

    @Test
    void sendableNowIsNullWhenNoMoneyLimitIsConfigured() {
        assertThat(PaymentLimitService.sendableNow(TierLimits.none(), used(5, 5, 1))).isNull();
        assertThat(PaymentLimitService.sendableNow(new TierLimits(null, null, null, 5), used(5, 5, 1)))
                .as("a count with payments left is not a money cap").isNull();
    }

    @Test
    void sendableNowNeverGoesNegativeAndIgnoresAbsentKinds() {
        assertThat(PaymentLimitService.sendableNow(new TierLimits(null, 100L, null, null), used(150, 150, 1)))
                .isZero();
        assertThat(PaymentLimitService.sendableNow(new TierLimits(null, null, 700L, null), used(0, 200, 1)))
                .isEqualTo(500L);
    }

    /** A payment of exactly sendableNowMinor passes evaluate; one unit more does not. */
    @Test
    void sendableNowAgreesWithTheRefusal() {
        LimitUsage usage = used(120000, 81000, 3);
        long sendable = PaymentLimitService.sendableNow(BASIC, usage);
        assertThat(PaymentLimitService.evaluate(BASIC, usage, sendable, windows, KycTier.BASIC)).isEmpty();
        assertThat(PaymentLimitService.evaluate(BASIC, usage, sendable + 1, windows, KycTier.BASIC)).isPresent();
    }

    // --- /me/limits ------------------------------------------------------

    @Test
    void theViewListsOnlyConfiguredKindsWithTheSwaggerExamplesNumbers() {
        LimitsResponse view = PaymentLimitService.assemble(KycTier.BASIC, "USD", 50000L, BASIC,
                new LimitUsage(12500, 0, 3, 81000, 5000), windows);

        assertThat(view.kycTier()).isEqualTo("basic");
        assertThat(view.currency()).isEqualTo("USD");
        assertThat(view.stepUpAtOrAboveMinor()).isEqualTo(50000L);
        assertThat(view.sendableNowMinor()).isEqualTo(100000L);
        assertThat(view.limits()).extracting(LimitsResponse.LimitView::kind)
                .containsExactly("per_payment", "daily", "monthly", "daily_count");
        assertThat(view.limits().get(0)).isEqualTo(new LimitsResponse.LimitView(
                "per_payment", 100000L, null, null, null, null, null, null, null));
        assertThat(view.limits().get(1)).isEqualTo(new LimitsResponse.LimitView(
                "daily", 150000L, 12500L, 0L, 137500L, null, null, null, "2026-10-29T00:00:00+02:00"));
        assertThat(view.limits().get(2)).isEqualTo(new LimitsResponse.LimitView(
                "monthly", 500000L, 81000L, 5000L, 419000L, null, null, null, "2026-11-01T00:00:00+02:00"));
        assertThat(view.limits().get(3)).isEqualTo(new LimitsResponse.LimitView(
                "daily_count", null, null, null, null, 10L, 3L, 7L, "2026-10-29T00:00:00+02:00"));
    }

    @Test
    void aDormantTierShowsNoLimitsAndNoSendableFigure() {
        LimitsResponse view = PaymentLimitService.assemble(KycTier.STANDARD, "USD", null, TierLimits.none(),
                LimitUsage.NONE, windows);
        assertThat(view.limits()).isEmpty();
        assertThat(view.sendableNowMinor()).isNull();
        assertThat(view.stepUpAtOrAboveMinor()).isNull();
    }

    @Test
    void viewForReadsTheCustomersTierAndTheSameUsageQuery() {
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer("standard")));
        when(usageStore.usage(eq(CUSTOMER), eq("USD"), any())).thenReturn(used(1000, 2000, 1));
        when(stepUp.thresholdAtOrAbove(KycTier.STANDARD)).thenReturn(200000L);

        LimitsResponse view = service.viewFor(CUSTOMER);

        assertThat(view.kycTier()).isEqualTo("standard");
        assertThat(view.stepUpAtOrAboveMinor()).isEqualTo(200000L);
        assertThat(view.limits().get(1).usedMinor()).isEqualTo(1000L);
        verify(usageStore).usage(CUSTOMER, "USD", windows);
        verify(usageStore, never()).lockCustomer(any());
    }

    @Test
    void viewForAnUnknownCustomerIs404() {
        when(customers.findById(CUSTOMER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewFor(CUSTOMER)).isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void aGarbageStoredTierIsReadAsBasicNeverAnException() {
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer("PLATINUM")));
        when(usageStore.usage(any(), any(), any())).thenReturn(LimitUsage.NONE);

        assertThat(service.viewFor(CUSTOMER).kycTier()).isEqualTo("basic");
        assertThat(meters.counter("innbucks.kyc_tier.unrecognised").count()).isEqualTo(1.0);
    }

    // --- advisory pre-check ---------------------------------------------------

    @Test
    void theAdvisoryCheckRefusesWithTheRefusalFields() {
        when(usageStore.usage(CUSTOMER, "USD", windows)).thenReturn(used(140000, 140000, 2));

        assertThatThrownBy(() -> service.precheck(CUSTOMER, KycTierResolution.of(KycTier.BASIC),
                LedgerTransactionType.TRANSFER, 20000, KEY))
                .isInstanceOf(PaymentLimitExceededException.class)
                .satisfies(ex -> {
                    LimitRefusal r = ((PaymentLimitExceededException) ex).refusal();
                    assertThat(r.kind()).isEqualTo(LimitKind.DAILY);
                    assertThat(r.remainingMinor()).isEqualTo(10000L);
                });
        verify(usageStore, never()).lockCustomer(any());
        assertThat(meters.counter("innbucks.payment_limits.refused",
                "limit", "daily", "check", "advisory", "outcome", "refused").count()).isEqualTo(1.0);
    }

    /**
     * The replay trap: a payment that took the customer exactly to the limit,
     * retried with the SAME key, must get its recorded outcome — not be
     * counted against itself and refused.
     */
    @Test
    void theAdvisoryCheckSkipsAKeyTheLedgerAlreadyHolds() {
        when(ledger.findByExternalRef(KEY)).thenReturn(Optional.of(new LedgerTransaction()));
        when(usageStore.usage(any(), any(), any())).thenReturn(used(150000, 150000, 10));

        service.precheck(CUSTOMER, KycTierResolution.of(KycTier.BASIC), LedgerTransactionType.TRANSFER,
                150000, KEY);

        verify(usageStore, never()).usage(any(), any(), any());
    }

    @Test
    void depositsAreNeverLimited() {
        service.precheck(CUSTOMER, KycTierResolution.of(KycTier.BASIC), LedgerTransactionType.DEPOSIT,
                Long.MAX_VALUE, KEY);
        verifyNoInteractions(usageStore, ledger);
    }

    @Test
    void aDormantPolicyCostsNoQueryAtAll() {
        PaymentLimitService dormant = service(policy(TierLimits.none(), TierLimits.none(), TierLimits.none()));
        dormant.precheck(CUSTOMER, KycTierResolution.of(KycTier.BASIC), LedgerTransactionType.WITHDRAWAL,
                Long.MAX_VALUE, KEY);
        dormant.reservationGuard(KycTierResolution.of(KycTier.BASIC)).check(draft(Long.MAX_VALUE / 2), NOW);
        verifyNoInteractions(usageStore, ledger);
    }

    @Test
    void aPerPaymentOnlyPolicyNeedsNoUsageAndNoLock() {
        PaymentLimitService perPaymentOnly = service(policy(new TierLimits(100L, null, null, null),
                TierLimits.none(), TierLimits.none()));

        perPaymentOnly.reservationGuard(KycTierResolution.of(KycTier.BASIC)).check(draft(100), NOW);
        assertThatThrownBy(() -> perPaymentOnly.reservationGuard(KycTierResolution.of(KycTier.BASIC))
                .check(draft(101), NOW)).isInstanceOf(PaymentLimitExceededException.class);
        verifyNoInteractions(usageStore);
    }

    @Test
    void aRefusalOnAnUnreadableTierIs503NeverLimitExceeded() {
        when(usageStore.usage(any(), any(), any())).thenReturn(used(150000, 150000, 1));

        assertThatThrownBy(() -> service.precheck(CUSTOMER, KycTierResolution.unavailable(),
                LedgerTransactionType.TRANSFER, 1, KEY))
                .isInstanceOf(KycTierUnavailableException.class);
        // ...while what BASIC allows still goes through on the BASIC floor.
        when(usageStore.usage(any(), any(), any())).thenReturn(used(0, 0, 0));
        service.precheck(CUSTOMER, KycTierResolution.unavailable(), LedgerTransactionType.TRANSFER, 1000, KEY);
    }

    // --- binding check ------------------------------------------------------

    @Test
    void theBindingCheckLocksTheCustomerBeforeSummingAndUsesTheRowsOwnInstant() {
        Instant openedAt = Instant.parse("2026-10-28T23:30:00Z"); // 01:30 on the 29th, Harare
        LimitWindows expected = LimitWindows.at(openedAt, Country.ZW.zoneId());
        when(usageStore.usage(CUSTOMER, "USD", expected)).thenReturn(used(0, 0, 0));

        service.reservationGuard(KycTierResolution.of(KycTier.BASIC)).check(draft(1000), openedAt);

        InOrder order = inOrder(usageStore);
        order.verify(usageStore).lockCustomer(CUSTOMER);
        order.verify(usageStore).usage(CUSTOMER, "USD", expected);
    }

    @Test
    void theBindingCheckRefusesWhatTheAdvisoryCheckLetThrough() {
        // The advisory read saw room; by the time this payment holds the lock,
        // a concurrent one has committed its PENDING row.
        when(usageStore.usage(CUSTOMER, "USD", windows)).thenReturn(used(100000, 100000, 1));

        assertThatThrownBy(() -> service.reservationGuard(KycTierResolution.of(KycTier.BASIC))
                .check(draft(60000), NOW))
                .isInstanceOf(PaymentLimitExceededException.class);
        assertThat(meters.counter("innbucks.payment_limits.refused",
                "limit", "daily", "check", "binding", "outcome", "refused").count()).isEqualTo(1.0);
    }

    @Test
    void theBindingCheckIgnoresDeposits() {
        LedgerDraft deposit = new LedgerDraft(CUSTOMER, LedgerTransactionType.DEPOSIT, null, "w",
                new MinorUnits(Long.MAX_VALUE / 2, "USD"), null, KEY, CoreProvider.FINERACT);
        service.reservationGuard(KycTierResolution.of(KycTier.BASIC)).check(deposit, NOW);
        verifyNoInteractions(usageStore);
    }

    private static LedgerDraft draft(long amountMinor) {
        return new LedgerDraft(CUSTOMER, LedgerTransactionType.TRANSFER, CUSTOMER + ":wallet", "other:wallet",
                new MinorUnits(amountMinor, "USD"), "rent", KEY, CoreProvider.FINERACT);
    }

    private static Customer customer(String tier) {
        Customer c = new Customer();
        c.setId(CUSTOMER);
        c.setKycTier(tier);
        return c;
    }
}
