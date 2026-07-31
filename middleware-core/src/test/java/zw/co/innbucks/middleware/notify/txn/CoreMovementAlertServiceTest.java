package zw.co.innbucks.middleware.notify.txn;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.ledger.LedgerTransaction;
import zw.co.innbucks.middleware.ledger.LedgerTransactionRepository;
import zw.co.innbucks.middleware.notify.NotificationDeliveryException;
import zw.co.innbucks.middleware.otp.SmsSender;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The core-reported-movement path: who gets told about money the middleware
 * did not move. The two cases that matter most are the dedup (or every
 * app-initiated deposit would SMS twice) and the positive ownership check (or
 * a hook payload could route one customer's amounts to another's phone).
 */
class CoreMovementAlertServiceTest {

    private static final UUID CUSTOMER = UUID.fromString("9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d");
    private static final String ACCT = CUSTOMER + ":wallet";

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final LedgerTransactionRepository ledger = mock(LedgerTransactionRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final SmsSender sms = mock(SmsSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CoreMovementAlertService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        Customer customer = new Customer();
        customer.setId(CUSTOMER);
        customer.setMsisdn("+263782606983");
        customer.setCoreExternalId(CUSTOMER.toString());
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(ledger.findByExternalRef(anyString())).thenReturn(Optional.empty());
        when(port.listDepositAccounts(new CoreCustomerRef(CUSTOMER.toString())))
                .thenReturn(List.of(new DepositAccountSummary(
                        new AccountRef(ACCT), "Wallet", "USD", new MinorUnits(61500, "USD"))));
        service = new CoreMovementAlertService(
                new TransactionNotificationProperties(true, false, "Africa/Harare", 160, 24),
                new TransactionMessageComposer(ZoneId.of("Africa/Harare"), 160, 24),
                customers, ledger, provider, sms, meterRegistry);
    }

    private static CoreMovementObserved movement(String account, String externalRef) {
        return new CoreMovementObserved(account, TransactionDirection.CREDIT,
                new MinorUnits(60000, "USD"), "Deposit", externalRef, "42",
                Instant.parse("2026-07-31T12:05:00Z"));
    }

    @Test
    void aTellerDepositAlertsTheAccountsOwner() {
        CoreMovementAlertService.Outcome outcome = service.alert(movement(ACCT, null));

        assertThat(outcome).isEqualTo(CoreMovementAlertService.Outcome.SENT);
        verify(sms).send(eq("+263782606983"), org.mockito.ArgumentMatchers.contains(
                "Account ending 6c5d credited with USD 600.00"));
    }

    /**
     * THE dedup. A movement whose reference is in OUR ledger is one the
     * middleware initiated — its settlement already alerts via the ledger
     * seam, and a second SMS here would double every app deposit.
     */
    @Test
    void ourOwnMovementEchoedBackByTheHookIsDroppedNotDoubled() {
        when(ledger.findByExternalRef("our-ref")).thenReturn(Optional.of(new LedgerTransaction()));

        CoreMovementAlertService.Outcome outcome = service.alert(movement(ACCT, "our-ref"));

        assertThat(outcome).isEqualTo(CoreMovementAlertService.Outcome.DEDUPED_OURS);
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void aReferenceWeDidNotMintStillAlerts() {
        // The core can attach its own references; only OUR ledger's refs dedup.
        CoreMovementAlertService.Outcome outcome = service.alert(movement(ACCT, "FIN-BATCH-881"));

        assertThat(outcome).isEqualTo(CoreMovementAlertService.Outcome.SENT);
    }

    @Test
    void anAccountOutsideOurNamingConventionIsSkipped() {
        assertThat(service.alert(movement("TELLER-SUSPENSE-01", null)))
                .isEqualTo(CoreMovementAlertService.Outcome.NO_CUSTOMER_MAPPING);
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void theNamingConventionAloneIsNotProofOfOwnership() {
        // Core does not list the account for that customer -> nothing is sent.
        when(port.listDepositAccounts(any())).thenReturn(List.of());

        assertThat(service.alert(movement(ACCT, null)))
                .isEqualTo(CoreMovementAlertService.Outcome.OWNERSHIP_UNPROVEN);
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void anUnprovableOwnershipCheckMeansNoMessageNotAGuess() {
        when(port.listDepositAccounts(any())).thenThrow(new RuntimeException("core down"));

        assertThat(service.alert(movement(ACCT, null)))
                .isEqualTo(CoreMovementAlertService.Outcome.OWNERSHIP_UNPROVEN);
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void theMasterSwitchSilencesThisPathToo() {
        service = new CoreMovementAlertService(
                new TransactionNotificationProperties(false, false, "Africa/Harare", 160, 24),
                new TransactionMessageComposer(ZoneId.of("Africa/Harare"), 160, 24),
                customers, ledger, mock(ObjectProvider.class), sms, meterRegistry);

        assertThat(service.alert(movement(ACCT, null)))
                .isEqualTo(CoreMovementAlertService.Outcome.DISABLED);
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void aDeadSmsGatewayIsCountedNeverThrown() {
        doThrow(new NotificationDeliveryException("gateway 503")).when(sms).send(anyString(), anyString());

        assertThatCode(() -> {
            CoreMovementAlertService.Outcome outcome = service.alert(movement(ACCT, null));
            assertThat(outcome).isEqualTo(CoreMovementAlertService.Outcome.FAILED);
        }).doesNotThrowAnyException();
    }

    @Test
    void everyOutcomeLandsInTheSharedNotificationMetric() {
        service.alert(movement(ACCT, null));

        assertThat(meterRegistry.counter("innbucks.transaction.notifications",
                "type", "CORE_CREDIT", "leg", "credit", "outcome", "sent").count()).isEqualTo(1.0);
    }
}
