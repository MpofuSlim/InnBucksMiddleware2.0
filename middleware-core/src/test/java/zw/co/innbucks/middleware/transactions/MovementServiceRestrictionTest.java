package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.config.CoreBankingExceptionHandler;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRecordKycTierResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.ledger.LedgeredMovementExecutor;
import zw.co.innbucks.middleware.limits.PaymentLimitService;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;
import zw.co.innbucks.middleware.stepup.StepUpService;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementRequests.WithdrawRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A payment from an account the bank has blocked is refused from the
 * ownership listing alone — 403 {@code account_restricted} before the step-up
 * token is spent, before the idempotency claim, before any ledger row or core
 * write — unless the key already ran, whose recorded outcome stays the answer.
 * And a same-key replay never hands back the adapter's ops detail.
 * The HTTP + Postgres half is in {@code TransactionFlowIntegrationTest}.
 */
class MovementServiceRestrictionTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final LedgeredMovementExecutor executor = mock(LedgeredMovementExecutor.class);
    private final LedgerTransactionRepository ledger = mock(LedgerTransactionRepository.class);
    private final StepUpService stepUp = mock(StepUpService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // The limits and the request budget are not under test here (their order
    // against the block is, in MovementServiceTest): they let everything by.
    private final PaymentLimitService limits = mock(PaymentLimitService.class);
    private final MovementService service = new MovementService(
            customers, port, idempotency, executor, ledger, stepUp,
            new CustomerRecordKycTierResolver(meters), limits, mock(RateLimiterService.class), meters);

    private final UUID customerId = UUID.randomUUID();
    private final String wallet = customerId + ":wallet";

    @BeforeEach
    void setUp() {
        Customer customer = new Customer();
        customer.setId(customerId);
        customer.setKycTier("basic");
        customer.setCoreExternalId(customerId.toString());
        when(customers.findById(customerId)).thenReturn(Optional.of(customer));
        when(port.provider()).thenReturn(CoreProvider.FINERACT);
        when(ledger.findByExternalRef(anyString())).thenReturn(Optional.empty());
    }

    private void walletIs(AccountRestriction restriction) {
        when(port.listDepositAccountRefs(new CoreCustomerRef(customerId.toString())))
                .thenReturn(List.of(new DepositAccountRef(new AccountRef(wallet), "InnBucks Wallet", "USD",
                        "000000010", restriction)));
    }

    private WithdrawRequest withdrawal() {
        return new WithdrawRequest(wallet, 5000, "USD", null);
    }

    private TransferRequest transfer() {
        return new TransferRequest(wallet, "other:wallet", 5000, "USD", null);
    }

    private double refused(String type) {
        return meters.counter("innbucks.transactions.restricted_refusals", "type", type).count();
    }

    @Test
    void aWithdrawalFromADebitBlockedWalletIsRefusedBeforeAnythingIsWritten() {
        walletIs(AccountRestriction.DEBITS_BLOCKED);

        assertThatThrownBy(() -> service.withdraw(customerId, "wd-1", withdrawal(), "step-up-token"))
                .isInstanceOf(CoreAccountRestrictedException.class);

        verifyNoInteractions(idempotency, executor);
        // The SMS approval is not spent on a payment that cannot go anywhere.
        verify(stepUp, never()).enforce(any(), any(), any(), any(), any(), anyLong(), any(), any());
        assertThat(refused("withdrawal")).isEqualTo(1.0);
    }

    @Test
    void aTransferFromAFullyBlockedWalletIsRefusedBeforeAnythingIsWritten() {
        walletIs(AccountRestriction.FULLY_BLOCKED);

        assertThatThrownBy(() -> service.transfer(customerId, "tr-1", transfer(), null))
                .isInstanceOf(CoreAccountRestrictedException.class);

        verifyNoInteractions(idempotency, executor);
        verify(stepUp, never()).enforce(any(), any(), any(), any(), any(), anyLong(), any(), any());
        assertThat(refused("transfer")).isEqualTo(1.0);
    }

    @Test
    void aCreditOnlyBlockDoesNotStopAPaymentOut() {
        walletIs(AccountRestriction.CREDITS_BLOCKED);

        service.transfer(customerId, "tr-2", transfer(), null);

        verify(idempotency).execute(eq(IdempotencyKeys.namespaced(customerId.toString(), "tr-2")),
                eq("POST"), eq("/transactions/transfer"), any(), eq(TransactionResponse.class), any());
        assertThat(refused("transfer")).isZero();
    }

    @Test
    void anUnblockedWalletProceedsAsBefore() {
        walletIs(AccountRestriction.NONE);

        service.withdraw(customerId, "wd-2", withdrawal(), null);

        verify(idempotency).execute(anyString(), eq("POST"), eq("/transactions/withdraw"), any(),
                eq(TransactionResponse.class), any());
    }

    @Test
    void aKeyThatAlreadyRanIsAnsweredFromItsLedgerRowEvenAfterTheBlock() {
        // The payment went through, the response was lost, THEN the bank
        // blocked the wallet. The retry must learn it succeeded — "account
        // restricted" would read as "your payment failed".
        walletIs(AccountRestriction.FULLY_BLOCKED);
        String key = IdempotencyKeys.namespaced(customerId.toString(), "tr-3");
        when(ledger.findByExternalRef(key)).thenReturn(Optional.of(new LedgerTransaction()));

        service.transfer(customerId, "tr-3", transfer(), null);

        verify(idempotency).execute(eq(key), eq("POST"), eq("/transactions/transfer"), any(),
                eq(TransactionResponse.class), any());
        assertThat(refused("transfer")).isZero();
    }

    @Test
    void anAccountTheCallerDoesNotOwnIsStillAnOwnershipRefusalNotARestriction() {
        walletIs(AccountRestriction.FULLY_BLOCKED);

        assertThatThrownBy(() -> service.withdraw(customerId, "wd-3",
                new WithdrawRequest("someone-else:wallet", 5000, "USD", null), null))
                .isInstanceOf(AccountOwnershipException.class);
    }

    // ------------------------------------------------------- replay copy

    private static LedgerTransaction failedRow(String code, String message) {
        LedgerTransaction row = new LedgerTransaction();
        row.setFailureCode(code);
        row.setFailureMessage(message);
        return row;
    }

    @Test
    void aReplayOfAnUnreachableOrRefusedCoreNeverRepeatsTheAdaptersDetail() {
        // The first answer was a generic 503/502; the stored message is ops
        // detail that can quote the core — about another account, on a transfer.
        for (String code : List.of(LedgeredMovementExecutor.CORE_UNREACHABLE,
                LedgeredMovementExecutor.CORE_SERVER_ERROR, LedgeredMovementExecutor.CORE_AUTH_FAILED)) {
            assertThat(MovementService.replayedFailureMessage(failedRow(code,
                    "Fineract read failed (500): error.msg.saving.account.id.invalid: "
                            + "Savings account with identifier 12 does not exist")))
                    .as(code)
                    .isEqualTo(MovementService.NOT_APPLIED_CORE_UNAVAILABLE)
                    .doesNotContain("Fineract");
        }
    }

    @Test
    void aReplayOfARestrictionRepeatsTheFixedCopy() {
        assertThat(MovementService.replayedFailureMessage(failedRow("account_restricted",
                "Fineract refused to debit a restricted account (error.msg.savings.account.debit.transaction.not.allowed)")))
                .isEqualTo(CoreBankingExceptionHandler.ACCOUNT_RESTRICTED_DETAIL);
    }

    @Test
    void aReplayOfACoreRefusalRepeatsTheFirstAnswersDetail() {
        // core_rejected: the 422's own detail, which the adapter already made
        // safe at the source for transfers.
        assertThat(MovementService.replayedFailureMessage(failedRow("core_rejected",
                "The receiving account cannot accept this payment.")))
                .isEqualTo("The receiving account cannot accept this payment.");
        assertThat(MovementService.replayedFailureMessage(failedRow("core_rejected", null)))
                .isEqualTo("The movement did not apply. Use a new Idempotency-Key for a fresh attempt.");
    }

    @Test
    void aReplayOfARowWithNoFailureCodeStillAnswers() {
        // fromExistingRow tolerates a null code; the replay copy must too.
        assertThat(MovementService.replayedFailureMessage(failedRow(null, "Core reported the movement did not apply")))
                .isEqualTo("Core reported the movement did not apply");
    }
}
