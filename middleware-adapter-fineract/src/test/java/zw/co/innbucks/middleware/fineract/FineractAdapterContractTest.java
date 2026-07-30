package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.MovementKind;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.math.BigDecimal;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter-level behaviour over the wire: resumable saga legs (positive
 * verification, never error-string sniffing), the amount-echo guard, the
 * reconciliation semantics per movement kind, and the cell-currency checks.
 */
class FineractAdapterContractTest {

    static WireMockServer wireMock;
    static FineractProperties properties;
    static FineractAdapter adapter;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        properties = FineractContractTestSupport.properties(wireMock.port());
        adapter = new FineractAdapter(FineractContractTestSupport.client(properties), properties);
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    private static CreateCustomerCommand customer(String externalId) {
        return new CreateCustomerCommand(externalId, "+254712000001", "Tariro", "Moyo", Map.of());
    }

    @Test
    void createCustomerResumesWhenAPreviousAttemptAlreadyCreatedTheClient() {
        // The create is rejected (duplicate externalId shape) …
        wireMock.stubFor(post(urlEqualTo("/v1/clients"))
                .willReturn(aResponse().withStatus(403).withBody("""
                        {"errors":[{"userMessageGlobalisationCode":"error.msg.client.duplicate.externalId",
                                    "defaultUserMessage":"Client with externalId already exists"}]}
                        """)));
        // … and positive verification finds the earlier attempt's client.
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/cust-9"))
                .willReturn(okJson("{\"id\":9,\"externalId\":\"cust-9\",\"active\":true}")));

        CoreCustomerRef ref = adapter.createCustomer(customer("cust-9"), new IdempotencyKey("key-9"));

        assertThat(ref.externalId()).isEqualTo("cust-9");
    }

    @Test
    void openDepositAccountRunsTheFullSagaWithPerLegKeys() {
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/cust-1%3Awallet"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/cust-1"))
                .willReturn(okJson("{\"id\":7,\"externalId\":\"cust-1\",\"active\":true}")));
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts"))
                .willReturn(okJson("{\"resourceId\":11,\"savingsId\":11}")));
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts/11?command=approve"))
                .willReturn(okJson("{\"resourceId\":11}")));
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts/11?command=activate"))
                .willReturn(okJson("{\"resourceId\":11}")));

        AccountRef ref = adapter.openDepositAccount(new CoreCustomerRef("cust-1"),
                "cust-1:wallet", new IdempotencyKey("reg-key"));

        assertThat(ref.externalId()).isEqualTo("cust-1:wallet");
        // Each leg is dedup'd upstream under its OWN key — a crash between legs
        // must not make a retry replay the wrong leg's cached result.
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/savingsaccounts"))
                .withHeader("Idempotency-Key", equalTo("reg-key:create")));
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/savingsaccounts/11?command=approve"))
                .withHeader("Idempotency-Key", equalTo("reg-key:approve")));
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/savingsaccounts/11?command=activate"))
                .withHeader("Idempotency-Key", equalTo("reg-key:activate")));
    }

    @Test
    void sagaResumesPastAnAlreadyApprovedLegByReReadingState() {
        // The account exists from a crashed earlier attempt, already approved.
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/cust-2%3Awallet"))
                .willReturn(okJson("""
                        {"id":12,"externalId":"cust-2:wallet","clientId":8,
                         "status":{"submittedAndPendingApproval":false,"approved":true,"active":false},
                         "currency":{"code":"KES"}}
                        """)));
        // Re-approving is vetoed (wrong state) — the adapter must VERIFY and move on …
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts/12?command=approve"))
                .willReturn(aResponse().withStatus(403).withBody("""
                        {"errors":[{"userMessageGlobalisationCode":"error.msg.savingsaccount.not.in.submittedandpendingapproval.state",
                                    "defaultUserMessage":"Not in submitted and pending approval state"}]}
                        """)));
        // … and complete the remaining activate leg.
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts/12?command=activate"))
                .willReturn(okJson("{\"resourceId\":12}")));

        AccountRef ref = adapter.openDepositAccount(new CoreCustomerRef("cust-2"),
                "cust-2:wallet", new IdempotencyKey("reg-key-2"));

        assertThat(ref.externalId()).isEqualTo("cust-2:wallet");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/savingsaccounts")));
    }

    @Test
    void depositWithAWrongCurrencyNeverTouchesTheNetwork() {
        assertThatThrownBy(() -> adapter.deposit(new MoneyMovementCommand(
                        new AccountRef("acct-1"), new MinorUnits(1000L, "USD"), "x", new TxRef("ref-usd")),
                new IdempotencyKey("key-usd")))
                .isInstanceOf(zw.co.innbucks.middleware.corebanking.exception.CoreClientException.class)
                .hasMessageContaining("cell currency");
        wireMock.verify(0, postRequestedFor(urlEqualTo(
                "/v1/savingsaccounts/external-id/acct-1/transactions?command=deposit")));
    }

    @Test
    void amountEchoMismatchParksTheMovementAsUnknown() {
        // Fineract echoes a DIFFERENT amount than we sent — money moved in a
        // shape we didn't ask for. Never reported as success.
        wireMock.stubFor(post(urlEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions?command=deposit"))
                .willReturn(okJson("{\"resourceId\":99,\"changes\":{\"transactionAmount\":2500.00}}")));

        assertThatThrownBy(() -> adapter.deposit(new MoneyMovementCommand(
                        new AccountRef("acct-1"), new MinorUnits(2500L, "KES"), "x", new TxRef("ref-echo")),
                new IdempotencyKey("key-echo")))
                .isInstanceOf(CoreUnknownOutcomeException.class)
                .hasMessageContaining("echoed amount");
    }

    @Test
    void depositReconciliationFindsTheTransactionByRef() {
        wireMock.stubFor(get(urlEqualTo(
                "/v1/savingsaccounts/external-id/acct-1/transactions/external-id/ref-77"))
                .willReturn(okJson("{\"id\":501,\"amount\":25.50,\"reversed\":false}")));

        TransactionResult result = adapter.getTransaction(new TransactionLookup(
                new TxRef("ref-77"), MovementKind.DEPOSIT, null, new AccountRef("acct-1")));

        assertThat(result.state()).isEqualTo(TransactionState.COMPLETED);
        assertThat(result.ref().reference()).isEqualTo("501");
    }

    @Test
    void reversedTransactionReconcilesAsFailed() {
        wireMock.stubFor(get(urlEqualTo(
                "/v1/savingsaccounts/external-id/acct-1/transactions/external-id/ref-78"))
                .willReturn(okJson("{\"id\":502,\"amount\":25.50,\"reversed\":true}")));

        TransactionResult result = adapter.getTransaction(new TransactionLookup(
                new TxRef("ref-78"), MovementKind.WITHDRAWAL, new AccountRef("acct-1"), null));

        assertThat(result.state()).isEqualTo(TransactionState.FAILED);
    }

    @Test
    void absentSavingsTransactionIsAPositiveNeverLandedFailed() {
        // Our deposit/withdraw writes ALWAYS attach the ref, so 404-by-ref is
        // a positive "this write never landed".
        wireMock.stubFor(get(urlEqualTo(
                "/v1/savingsaccounts/external-id/acct-1/transactions/external-id/ref-79"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));

        TransactionResult result = adapter.getTransaction(new TransactionLookup(
                new TxRef("ref-79"), MovementKind.DEPOSIT, null, new AccountRef("acct-1")));

        assertThat(result.state()).isEqualTo(TransactionState.FAILED);
    }

    @Test
    void absentTransferStaysUnknownBecauseTransfersCannotCarryOurRef() {
        wireMock.stubFor(get(urlEqualTo("/v1/accounttransfers?externalId=ref-80"))
                .willReturn(okJson("{\"totalFilteredRecords\":0,\"pageItems\":[]}")));

        TransactionResult result = adapter.getTransaction(new TransactionLookup(
                new TxRef("ref-80"), MovementKind.TRANSFER, null, null));

        // Absence proves nothing for transfers — the row stays parked.
        assertThat(result.state()).isEqualTo(TransactionState.UNKNOWN);
    }

    @Test
    void balanceMapsToMinorUnitsAndRejectsAWrongCurrencyCell() {
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/acct-kes"))
                .willReturn(okJson("""
                        {"id":21,"externalId":"acct-kes","clientId":7,
                         "status":{"active":true},"currency":{"code":"KES"},
                         "summary":{"accountBalance":150.75,"availableBalance":120.25}}
                        """)));
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/acct-usd"))
                .willReturn(okJson("""
                        {"id":22,"externalId":"acct-usd","clientId":7,
                         "status":{"active":true},"currency":{"code":"USD"},
                         "summary":{"accountBalance":10.00,"availableBalance":10.00}}
                        """)));

        AccountBalance balance = adapter.getBalance(new AccountRef("acct-kes"));
        assertThat(balance.current()).isEqualTo(new MinorUnits(15075L, "KES"));
        assertThat(balance.available()).isEqualTo(new MinorUnits(12025L, "KES"));

        // A wrong-currency account in this cell is a provisioning fault, not data.
        assertThatThrownBy(() -> adapter.getBalance(new AccountRef("acct-usd")))
                .isInstanceOf(CoreServerException.class)
                .hasMessageContaining("cell currency");
    }
}
