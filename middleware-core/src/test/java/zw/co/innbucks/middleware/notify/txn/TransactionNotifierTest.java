package zw.co.innbucks.middleware.notify.txn;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerNameResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.ProfileCacheProperties;
import zw.co.innbucks.middleware.ledger.LedgerStatus;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.notify.NotificationDeliveryException;
import zw.co.innbucks.middleware.otp.SmsSender;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing and guard rails. The composer's wording is pinned separately; what
 * matters here is WHO gets told, WHEN, and — above all — that nothing this
 * class does can escape and touch the movement that triggered it.
 */
class TransactionNotifierTest {

    private static final UUID SENDER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");
    private static final UUID RECIPIENT = UUID.fromString("9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d");
    private static final String SENDER_ACCT = SENDER + ":wallet";
    private static final String RECIPIENT_ACCT = RECIPIENT + ":wallet";

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TransactionNotifier notifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        when(port.getBalance(any())).thenAnswer(inv ->
                new AccountBalance(inv.getArgument(0), new MinorUnits(5000, "USD"), new MinorUnits(5000, "USD")));
        // Production resolves the sender's name from the core, so the default
        // here is the resolvable case; the fallback has its own test below.
        when(port.getProfile(any())).thenReturn(new CustomerProfile(
                new CoreCustomerRef(SENDER.toString()), "Tariro", "Mpofu", "active"));
        when(customers.findById(SENDER)).thenReturn(Optional.of(customer(SENDER, "+263782606983")));
        when(customers.findById(RECIPIENT)).thenReturn(Optional.of(customer(RECIPIENT, "+263771234567")));
        when(port.listDepositAccountRefs(new CoreCustomerRef(RECIPIENT.toString())))
                .thenReturn(List.of(new DepositAccountRef(
                        new AccountRef(RECIPIENT_ACCT), "Wallet", "USD", "000000010")));
        notifier = build(properties(true, false));
    }

    private TransactionNotifier build(TransactionNotificationProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        // A REAL resolver over the mock port, not a mocked resolver: these
        // tests should keep exercising the caching path production uses.
        return new TransactionNotifier(properties,
                new TransactionMessageComposer(ZoneId.of("Africa/Harare"), 160, 24),
                customers, provider, nameResolver(provider), sms, meterRegistry);
    }

    private static CustomerNameResolver nameResolver(ObjectProvider<CoreBankingPort> provider) {
        return new CustomerNameResolver(provider,
                new ProfileCacheProperties(true, Duration.ofMinutes(5), 1000),
                new SimpleMeterRegistry());
    }

    private static TransactionNotificationProperties properties(boolean enabled, boolean onFailure) {
        return new TransactionNotificationProperties(enabled, onFailure, "Africa/Harare", 160, 24);
    }

    private static Customer customer(UUID id, String msisdn) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setMsisdn(msisdn);
        customer.setCoreProvider("FINERACT");
        customer.setCoreExternalId(id.toString());
        return customer;
    }

    private static SettledMovementEvent event(LedgerTransactionType type, LedgerStatus status,
                                              String source, String destination) {
        return new SettledMovementEvent(UUID.randomUUID(), SENDER, type, status,
                source, destination, 500, "USD", null, "a".repeat(64), "15",
                Instant.parse("2026-07-31T12:05:00Z"));
    }

    @Test
    void depositTellsTheDepositorOnce() {
        notifier.deliver(event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, SENDER_ACCT));

        verify(sms).send(eq("+263782606983"), anyString());
        assertThat(counted("DEPOSIT", "credit", "sent")).isEqualTo(1.0);
    }

    @Test
    void withdrawalTellsTheAccountHolderOnce() {
        notifier.deliver(event(LedgerTransactionType.WITHDRAWAL, LedgerStatus.COMPLETED, SENDER_ACCT, null));

        verify(sms).send(eq("+263782606983"), anyString());
    }

    @Test
    void transferTellsBothSidesWithTheRightStoryEach() {
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sms, times(2)).send(to.capture(), body.capture());

        assertThat(to.getAllValues()).containsExactly("+263782606983", "+263771234567");
        assertThat(body.getAllValues().get(0))
                .contains("You sent USD 5.00 from your account ending 6e7f to account ending 6c5d");
        // The recipient is told WHO paid them, not which account number did.
        assertThat(body.getAllValues().get(1))
                .contains("Your account ending 6c5d has been credited with USD 5.00 from T.Mpofu");
    }

    @Test
    void whenTheSendersNameCannotBeReadTheRecipientStillGetsTheAlert() {
        // A core hiccup costs the message a name, never the message.
        when(port.getProfile(any())).thenThrow(new CoreTransientException(CoreProvider.FINERACT, "core down", null));

        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sms, times(2)).send(anyString(), body.capture());
        assertThat(body.getAllValues().get(1))
                .contains("credited with USD 5.00 from account ending 6e7f");
    }

    @Test
    void onlyTheRecipientsMessageNamesAParty() {
        // The sender picked the destination seconds ago and saw the name on the
        // confirm screen; naming it again costs characters and adds nothing.
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sms, times(2)).send(anyString(), body.capture());
        assertThat(body.getAllValues().get(0)).contains("to account ending 6c5d");
        assertThat(body.getAllValues().get(0)).doesNotContain("T.Mpofu");
    }

    @Test
    void aDepositNeverTriesToNameAnybody() {
        notifier.deliver(event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, SENDER_ACCT));

        verify(port, never()).getProfile(any());
    }

    @Test
    void balanceIsReadForTheLegItBelongsToNotTheInitiators() {
        // The recipient's message must quote the RECIPIENT's balance. Reading
        // the sender's for both would print one customer's balance to another.
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        // At least once each: the leg's own balance read plus the display-only
        // counterparty account-number read hit the same call.
        verify(port, org.mockito.Mockito.atLeastOnce()).getBalance(new AccountRef(SENDER_ACCT));
        verify(port, org.mockito.Mockito.atLeastOnce()).getBalance(new AccountRef(RECIPIENT_ACCT));
    }

    @Test
    void transferToSomeoneOutsideThisCellOnlyTellsTheSender() {
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, "SOME-EXTERNAL-ACCOUNT"));

        verify(sms, times(1)).send(anyString(), anyString());
    }

    @Test
    void aNamingConventionMatchIsNotEnoughToSendSomeoneElsesAmounts() {
        // The UUID prefix says "recipient", but the core does not list that
        // account for them — sending anyway would leak a stranger's movement.
        when(port.listDepositAccountRefs(new CoreCustomerRef(RECIPIENT.toString())))
                .thenReturn(List.of());

        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        verify(sms, times(1)).send(eq("+263782606983"), anyString());
    }

    @Test
    void movingMoneyBetweenYourOwnAccountsIsOneMessageNotTwo() {
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, SENDER + ":savings"));

        verify(sms, times(1)).send(anyString(), anyString());
    }

    @Test
    void failuresAreSilentByDefault() {
        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.FAILED,
                SENDER_ACCT, RECIPIENT_ACCT));

        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void whenFailureAlertsAreOnOnlyTheInitiatorHearsAboutIt() {
        // Nothing arrived for the other party — telling them a transfer they
        // never expected has failed is pure confusion.
        build(properties(true, true)).deliver(event(LedgerTransactionType.TRANSFER,
                LedgerStatus.FAILED, SENDER_ACCT, RECIPIENT_ACCT));

        verify(sms, times(1)).send(eq("+263782606983"), anyString());
    }

    @Test
    void theMasterSwitchStopsEverything() {
        build(properties(false, true)).deliver(event(LedgerTransactionType.DEPOSIT,
                LedgerStatus.COMPLETED, null, SENDER_ACCT));

        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void anUnreadableBalanceStillSendsTheAlert() {
        when(port.getBalance(any())).thenThrow(new CoreTransientException(CoreProvider.FINERACT, "core down", null));

        notifier.deliver(event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, SENDER_ACCT));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sms).send(anyString(), body.capture());
        assertThat(body.getValue()).contains("credited with USD 5.00").doesNotContain("Available balance");
    }

    @Test
    void aCustomerWithoutAnMsisdnIsSkippedAndCounted() {
        Customer noPhone = customer(SENDER, null);
        when(customers.findById(SENDER)).thenReturn(Optional.of(noPhone));

        notifier.deliver(event(LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, SENDER_ACCT));

        verify(sms, never()).send(anyString(), anyString());
        assertThat(counted("DEPOSIT", "credit", "skipped_no_msisdn")).isEqualTo(1.0);
    }

    /**
     * The invariant the whole design rests on: this runs in an after-commit
     * callback, and an exception escaping it propagates to the caller of
     * {@code commit()} — a dead SMS gateway would start making completed
     * deposits look like failures.
     */
    @Test
    void aDeadGatewayNeverEscapesToTheMovement() {
        doThrow(new NotificationDeliveryException("gateway 503"))
                .when(sms).send(anyString(), anyString());

        assertThatCode(() -> notifier.onSettled(event(
                LedgerTransactionType.DEPOSIT, LedgerStatus.COMPLETED, null, SENDER_ACCT)))
                .doesNotThrowAnyException();
        assertThat(counted("DEPOSIT", "credit", "failed")).isEqualTo(1.0);
    }

    @Test
    void oneLegFailingDoesNotStopTheOther() {
        doThrow(new NotificationDeliveryException("gateway 503"))
                .when(sms).send(eq("+263782606983"), anyString());

        notifier.deliver(event(LedgerTransactionType.TRANSFER, LedgerStatus.COMPLETED,
                SENDER_ACCT, RECIPIENT_ACCT));

        verify(sms).send(eq("+263771234567"), anyString());
        assertThat(counted("TRANSFER", "credit", "sent")).isEqualTo(1.0);
    }

    private double counted(String type, String leg, String outcome) {
        return meterRegistry.counter("innbucks.transaction.notifications",
                "type", type, "leg", leg, "outcome", outcome).count();
    }
}
