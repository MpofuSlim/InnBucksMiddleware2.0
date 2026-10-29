package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRecordKycTierResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerOutcome;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgeredMovementExecutor;
import zw.co.innbucks.middleware.ledger.PendingReservationGuard;
import zw.co.innbucks.middleware.limits.LimitKind;
import zw.co.innbucks.middleware.limits.LimitUsage;
import zw.co.innbucks.middleware.limits.LimitUsageStore;
import zw.co.innbucks.middleware.limits.PaymentLimitExceededException;
import zw.co.innbucks.middleware.limits.PaymentLimitService;
import zw.co.innbucks.middleware.limits.PaymentLimitsPolicy;
import zw.co.innbucks.middleware.limits.TierLimits;
import zw.co.innbucks.middleware.otp.ConsumedVerificationTokenStore;
import zw.co.innbucks.middleware.otp.VerificationTokenVerifier;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;
import zw.co.innbucks.middleware.stepup.StepUpProperties;
import zw.co.innbucks.middleware.stepup.StepUpRequiredException;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.MovementRequests.DepositRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ORDER of the money path's guards, which is where the limits' promises
 * live: the deposit switch before anything on a deposit, the payment request
 * budget before anything else, the limits before the step-up answer, a replay
 * answered from its ledger row instead of being counted again, and the
 * binding check handed to the ledger for withdrawals and transfers only.
 */
class MovementServiceTest {

    private static final UUID CUSTOMER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");
    private static final String WALLET = CUSTOMER + ":wallet";
    private static final Instant NOW = Instant.parse("2026-10-28T08:00:00Z");
    /** basic: per_payment 100,000 / daily 150,000 / monthly 500,000 / 10 a day. */
    private static final TierLimits BASIC = new TierLimits(100000L, 150000L, 500000L, 10);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final LedgeredMovementExecutor executor = mock(LedgeredMovementExecutor.class);
    private final LedgerTransactionRepository ledger = mock(LedgerTransactionRepository.class);
    private final LimitUsageStore usage = mock(LimitUsageStore.class);
    private final ConsumedVerificationTokenStore consumedTokens = mock(ConsumedVerificationTokenStore.class);
    private final StepUpService stepUp = new StepUpService(
            new StepUpProperties(true, new StepUpProperties.Thresholds(50000L, 200000L, 1000000L)),
            mock(VerificationTokenVerifier.class), consumedTokens, mock(AuditService.class), meters);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER);
        customer.setKycTier("basic");
        customer.setCoreExternalId(CUSTOMER.toString());
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(port.provider()).thenReturn(CoreProvider.FINERACT);
        when(port.listDepositAccountRefs(any(CoreCustomerRef.class))).thenReturn(List.of(
                new DepositAccountRef(new AccountRef(WALLET), "InnBucks Wallet", "USD", null)));
        when(ledger.findByExternalRef(anyString())).thenReturn(Optional.empty());
        when(usage.usage(any(), any(), any())).thenReturn(LimitUsage.NONE);
        // The idempotency layer is not under test: run the work as the claim owner.
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> new IdempotencyService.Result<>(
                        ((Supplier<Object>) inv.getArgument(5)).get(), 200, false));
        when(executor.execute(any(), any(PendingReservationGuard.class), any()))
                .thenReturn(new LedgerOutcome(UUID.randomUUID(), LedgerStatus.COMPLETED, "CORE-1"));
    }

    private MovementService service(TierLimits basic, KycTierResolver resolver, int paymentCapacity) {
        return service(basic, resolver, paymentCapacity, true);
    }

    private MovementService service(TierLimits basic, KycTierResolver resolver, int paymentCapacity,
                                    boolean depositsEnabled) {
        PaymentLimitsPolicy policy = new PaymentLimitsPolicy(Country.ZW, "USD", "limits/limits-zw.yaml",
                "0".repeat(64), Map.of(KycTier.BASIC, basic, KycTier.STANDARD, TierLimits.none(),
                KycTier.ENHANCED, TierLimits.none()), List.of());
        PaymentLimitService limits = new PaymentLimitService(policy, usage, ledger, customers, resolver, stepUp,
                new CountryProperties(Country.ZW), Clock.fixed(NOW, ZoneOffset.UTC), meters);
        Limit generous = new Limit(1000, Duration.ofMinutes(1));
        RateLimitProperties rateLimits = new RateLimitProperties(true, false, 0,
                generous, generous, generous, generous, generous, generous, generous, generous, generous,
                new Limit(paymentCapacity, Duration.ofMinutes(1)));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        DepositGate depositGate = new DepositGate(new DepositProperties(depositsEnabled), env, meters);
        return new MovementService(customers, port, idempotency, executor, ledger, stepUp, resolver, limits,
                new RateLimiterService(rateLimits), depositGate);
    }

    private MovementService service() {
        return service(BASIC, new CustomerRecordKycTierResolver(meters), 1000);
    }

    private static TransferRequest transfer(long amount) {
        return new TransferRequest(WALLET, "other:wallet", amount, "USD", "rent");
    }

    @Test
    void thePaymentRequestBudgetIsSpentBeforeAnythingElse() {
        MovementService service = service(BASIC, new CustomerRecordKycTierResolver(meters), 2);

        service.transfer(CUSTOMER, "k1", transfer(1000), null);
        service.deposit(CUSTOMER, "k2", new DepositRequest(WALLET, 1000, "USD", null));
        // One budget for all three endpoints: the third request is refused
        // before the customer is even loaded.
        assertThatThrownBy(() -> service.withdraw(CUSTOMER, "k3",
                new WithdrawRequest(WALLET, 1000, "USD", null), null))
                .isInstanceOf(RateLimitExceededException.class);
        verify(customers, times(2)).findById(CUSTOMER);
    }

    /**
     * The deposit switch sits AHEAD of the request budget all three endpoints
     * share. Its refusal costs nothing (no read, no write), and spending the
     * budget on it would let an app that still calls deposit on a switched-off
     * cell block the customer's withdrawals and transfers for the window.
     */
    @Test
    void aSwitchedOffDepositSpendsNoneOfThePaymentBudget() {
        MovementService service = service(BASIC, new CustomerRecordKycTierResolver(meters), 2, false);

        for (int i = 0; i < 5; i++) {
            String key = "d" + i;
            assertThatThrownBy(() -> service.deposit(CUSTOMER, key,
                    new DepositRequest(WALLET, 1000, "USD", null)))
                    .isInstanceOf(DepositDisabledException.class);
        }
        verifyNoInteractions(customers, executor, usage);
        assertThat(meters.get("innbucks.transactions.deposit.refused").counter().count()).isEqualTo(5.0);

        // The budget of two is untouched by the five refusals...
        assertThat(service.transfer(CUSTOMER, "t1", transfer(1000), null).body().status())
                .isEqualTo("SUCCESS");
        assertThat(service.withdraw(CUSTOMER, "w1", new WithdrawRequest(WALLET, 1000, "USD", null), null)
                .body().status()).isEqualTo("SUCCESS");
        // ...and still binds the movements that do run.
        assertThatThrownBy(() -> service.transfer(CUSTOMER, "t2", transfer(1000), null))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /** "Check the limits before answering step_up_required, so nobody is sent a code." */
    @Test
    void aPaymentTheLimitsRefuseIsNeverAnsweredStepUpRequired() {
        // 120,000 is over basic's step-up threshold AND its per-payment limit.
        assertThatThrownBy(() -> service().transfer(CUSTOMER, "k1", transfer(120000), null))
                .isInstanceOf(PaymentLimitExceededException.class)
                .satisfies(ex -> assertThat(((PaymentLimitExceededException) ex).refusal().kind())
                        .isEqualTo(LimitKind.PER_PAYMENT));
        verify(port, never()).listDepositAccountRefs(any());
        verifyNoInteractions(consumedTokens, executor);
    }

    /** The same promise on withdrawals — each endpoint carries its own pre-check call. */
    @Test
    void aWithdrawalTheLimitsRefuseIsNeverAnsweredStepUpRequired() {
        assertThatThrownBy(() -> service().withdraw(CUSTOMER, "k1",
                new WithdrawRequest(WALLET, 120000, "USD", null), null))
                .isInstanceOf(PaymentLimitExceededException.class)
                .satisfies(ex -> assertThat(((PaymentLimitExceededException) ex).refusal().kind())
                        .isEqualTo(LimitKind.PER_PAYMENT));
        verify(port, never()).listDepositAccountRefs(any());
        verifyNoInteractions(consumedTokens, executor);
    }

    @Test
    void aPaymentWithinTheLimitsStillNeedsItsApproval() {
        assertThatThrownBy(() -> service().transfer(CUSTOMER, "k1", transfer(60000), null))
                .isInstanceOf(StepUpRequiredException.class);
        verifyNoInteractions(executor);
    }

    @Test
    void aReplayOfAPaymentThatReachedTheLimitGetsItsRecordedOutcome() {
        String key = IdempotencyKeys.namespaced(CUSTOMER.toString(), "k-replay");
        LedgerTransaction row = new LedgerTransaction();
        row.setId(UUID.randomUUID());
        row.setStatus("COMPLETED");
        row.setCoreTxRef("CORE-9");
        row.setExternalRef(key);
        when(ledger.findByExternalRef(key)).thenReturn(Optional.of(row));
        // The day's allowance is fully used — by this very payment.
        when(usage.usage(any(), any(), any())).thenReturn(new LimitUsage(150000, 0, 4, 150000, 0));

        IdempotencyService.Result<TransactionResponse> result =
                service().transfer(CUSTOMER, "k-replay", transfer(40000), null);

        assertThat(result.body().status()).isEqualTo("SUCCESS");
        assertThat(result.body().coreTxRef()).isEqualTo("CORE-9");
        verify(usage, never()).usage(any(), any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void withdrawalsAndTransfersOpenTheirRowUnderTheBindingCheckDepositsDoNot() {
        MovementService service = service();

        service.transfer(CUSTOMER, "t1", transfer(1000), null);
        service.withdraw(CUSTOMER, "w1", new WithdrawRequest(WALLET, 1000, "USD", null), null);
        service.deposit(CUSTOMER, "d1", new DepositRequest(WALLET, 999_999_999, "USD", null));

        verify(executor, times(2)).execute(any(), argThat(g -> g != PendingReservationGuard.NONE), any());
        verify(executor).execute(any(), eq(PendingReservationGuard.NONE), any());
    }

    @Test
    void aStepUpRefusalOnAnUnreadableTierIs503() {
        KycTierResolver unreadable = c -> KycTierResolution.unavailable();
        MovementService service = service(TierLimits.none(), unreadable, 1000);

        assertThatThrownBy(() -> service.transfer(CUSTOMER, "k1", transfer(60000), null))
                .isInstanceOf(KycTierUnavailableException.class);
        // What BASIC lets through without an approval still goes through.
        assertThat(service.transfer(CUSTOMER, "k2", transfer(1000), null).body().status()).isEqualTo("SUCCESS");
    }
}
