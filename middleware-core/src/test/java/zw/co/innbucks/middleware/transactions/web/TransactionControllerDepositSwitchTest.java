package zw.co.innbucks.middleware.transactions.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.config.GlobalExceptionHandler;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerOutcome;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.ledger.LedgeredMovementExecutor;
import zw.co.innbucks.middleware.ledger.PendingReservationGuard;
import zw.co.innbucks.middleware.limits.PaymentLimitService;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.DepositGate;
import zw.co.innbucks.middleware.transactions.DepositProperties;
import zw.co.innbucks.middleware.transactions.MovementService;
import zw.co.innbucks.middleware.transactions.TransactionResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deposit switch over HTTP, without Spring or Docker: the real controller,
 * the real {@link MovementService}, the real {@link DepositGate} and the real
 * exception advice, with every collaborator that could read, claim, write or
 * call the core mocked — so "nothing happened" is a {@code verifyNoInteractions},
 * not an inference.
 *
 * <p>Method security ({@code @PreAuthorize}) is not in play standalone;
 * DepositDisabledFlowIntegrationTest drives the same refusal through the real
 * security chain and Postgres.
 */
class TransactionControllerDepositSwitchTest {

    private final CustomerRepository customerRepository = mock(CustomerRepository.class);
    private final CoreBankingPort corePort = mock(CoreBankingPort.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final LedgeredMovementExecutor executor = mock(LedgeredMovementExecutor.class);
    private final LedgerTransactionRepository ledgerRepository = mock(LedgerTransactionRepository.class);
    private final StepUpService stepUpService = mock(StepUpService.class);
    private final KycTierResolver tierResolver = mock(KycTierResolver.class);
    private final PaymentLimitService limitService = mock(PaymentLimitService.class);
    private final RateLimiterService rateLimiter = mock(RateLimiterService.class);

    private final UUID customerId = UUID.randomUUID();
    private final String wallet = customerId + ":wallet";
    private final String body = """
            {"accountId":"%s","amountMinor":250000,"currency":"KES","narrative":"cash in"}
            """.formatted(wallet);

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(customerId.toString()).claim("scope", "customer:write").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc(boolean depositsEnabled) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        DepositGate gate = new DepositGate(new DepositProperties(depositsEnabled), env, new SimpleMeterRegistry());
        MovementService service = new MovementService(customerRepository, corePort, idempotencyService,
                executor, ledgerRepository, stepUpService, tierResolver, limitService, rateLimiter, gate);
        return MockMvcBuilders.standaloneSetup(new TransactionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void offRefuses403BeforeAnyReadClaimLedgerRowOrCoreCall() throws Exception {
        mvc(false).perform(post("/transactions/deposit")
                        .header("Idempotency-Key", "dep-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Deposits unavailable"))
                .andExpect(jsonPath("$.detail").value("Deposits are not available in this deployment."))
                .andExpect(jsonPath("$.errorCode").value("deposit_disabled"));

        // No customer read, no ownership listing, no idempotency claim, no
        // crash-retry lookup, no ledger row, no core call, no step-up — and
        // none of the customer's /transactions/* request budget spent, no
        // tier read, no limit check.
        verifyNoInteractions(customerRepository, corePort, idempotencyService,
                executor, ledgerRepository, stepUpService, rateLimiter, tierResolver, limitService);
    }

    @Test
    void onRunsTheUsualGuardStackAndReachesTheCore() throws Exception {
        Customer customer = new Customer();
        customer.setId(customerId);
        customer.setCoreExternalId(customerId.toString());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(corePort.provider()).thenReturn(CoreProvider.FINERACT);
        when(corePort.listDepositAccountRefs(any())).thenReturn(List.of(
                new DepositAccountRef(new AccountRef(wallet), "InnBucks Wallet", "KES", null)));
        when(corePort.deposit(any(), any()))
                .thenReturn(new TransactionResult(new TxRef("CORE-501"), TransactionState.COMPLETED));
        when(ledgerRepository.findByExternalRef(anyString())).thenReturn(Optional.empty());
        // The claim runs the work, as the real one does on a fresh key.
        when(idempotencyService.execute(anyString(), eq("POST"), eq("/transactions/deposit"), any(),
                eq(TransactionResponse.class), any()))
                .thenAnswer(inv -> {
                    Supplier<TransactionResponse> work = inv.getArgument(5);
                    return new IdempotencyService.Result<>(work.get(), 200, false);
                });
        // The executor drives the core call, as the real one does after the PENDING row.
        UUID rowId = UUID.randomUUID();
        when(executor.execute(any(), any(), any())).thenAnswer(inv -> {
            Function<TxRef, TransactionResult> coreCall = inv.getArgument(2);
            TransactionResult result = coreCall.apply(new TxRef("ignored"));
            return new LedgerOutcome(rowId, LedgerStatus.COMPLETED, result.ref().reference());
        });

        mvc(true).perform(post("/transactions/deposit")
                        .header("Idempotency-Key", "dep-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.coreTxRef").value("CORE-501"))
                .andExpect(jsonPath("$.transactionId").value(rowId.toString()));

        // Switched on, a deposit spends the shared request budget like any
        // other movement, and is opened without a limits reservation.
        verify(rateLimiter).enforceCustomerPayment(customerId);
        var draft = forClass(LedgerDraft.class);
        verify(executor).execute(draft.capture(), eq(PendingReservationGuard.NONE), any());
        assertThat(draft.getValue().type()).isEqualTo(LedgerTransactionType.DEPOSIT);
        assertThat(draft.getValue().destinationAccount()).isEqualTo(wallet);

        var command = forClass(MoneyMovementCommand.class);
        verify(corePort, times(1)).deposit(command.capture(), any(IdempotencyKey.class));
        assertThat(command.getValue().amount().amount()).isEqualTo(250000L);
        assertThat(command.getValue().account().externalId()).isEqualTo(wallet);
    }
}
