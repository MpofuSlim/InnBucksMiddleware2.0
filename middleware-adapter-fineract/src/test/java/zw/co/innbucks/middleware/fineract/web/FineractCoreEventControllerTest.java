package zw.co.innbucks.middleware.fineract.web;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import zw.co.innbucks.middleware.corebanking.CoreMovementListener;
import zw.co.innbucks.middleware.corebanking.value.CoreMovementObserved;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.fineract.FineractContractTestSupport;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.middleware.fineract.FineractProperties;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook receiver's contract: token gating, the positive re-read (the
 * hook body is a TRIGGER — every customer-facing fact comes from Fineract's
 * API, not the payload), and the never-throw posture. The downstream listener
 * is captured, not mocked — this module has no mocking library and the seam
 * is one method.
 */
class FineractCoreEventControllerTest {

    private static final String TOKEN = "test-core-events-token";
    private static final String ACCT_EXTERNAL_ID = "9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d:wallet";

    private static WireMockServer wireMock;
    private static FineractProperties properties;

    private final CapturingListener listener = new CapturingListener();
    private FineractCoreEventController controller;

    static class CapturingListener implements CoreMovementListener {
        final List<CoreMovementObserved> received = new CopyOnWriteArrayList<>();

        @Override
        public void onCoreMovement(CoreMovementObserved movement) {
            received.add(movement);
        }
    }

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        properties = FineractContractTestSupport.properties(wireMock.port());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        controller = new FineractCoreEventController(
                FineractContractTestSupport.client(properties), properties, listener);
        stubAccount();
        stubTransaction("""
                {"id":42,"externalId":"","entryType":"CREDIT",
                 "transactionType":{"id":1,"value":"Deposit","deposit":true,"withdrawal":false},
                 "amount":600.000000,"runningBalance":615.000000,"reversed":false,"date":[2026,7,31]}
                """);
    }

    private static void stubAccount() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/10"))
                .willReturn(okJson("""
                        {"id":10,"externalId":"%s","clientId":27,
                         "status":{"active":true},"currency":{"code":"USD"}}
                        """.formatted(ACCT_EXTERNAL_ID))));
    }

    private static void stubTransaction(String body) {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/10/transactions/42"))
                .willReturn(okJson(body)));
    }

    private ResponseEntity<Void> post(String token, String entity, String action) {
        return controller.onEvent(token, entity, action,
                new FineractCoreEventController.HookEnvelope(
                        new FineractCoreEventController.HookResponse(10L, 42L), null));
    }

    @Test
    void aDepositEventIsReReadAndForwardedAsAnObservedCredit() {
        ResponseEntity<Void> response = post(TOKEN, "SAVINGSACCOUNT", "DEPOSIT");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(listener.received).hasSize(1);
        CoreMovementObserved movement = listener.received.get(0);
        assertThat(movement.accountExternalId()).isEqualTo(ACCT_EXTERNAL_ID);
        assertThat(movement.direction()).isEqualTo(TransactionDirection.CREDIT);
        // Amount from the RE-READ (600.000000 -> 60000 minor), never the hook body.
        assertThat(movement.amount().amount()).isEqualTo(60000L);
        assertThat(movement.externalRef()).isNull();
        assertThat(movement.coreTxRef()).isEqualTo("42");

        // The wire proof of the positive re-read: both GETs actually happened.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/10")));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/10/transactions/42")));
    }

    @Test
    void aWithdrawalEventForwardsAsADebit() {
        stubTransaction("""
                {"id":42,"externalId":"","entryType":"DEBIT",
                 "transactionType":{"id":2,"value":"Withdrawal","deposit":false,"withdrawal":true},
                 "amount":15.000000,"reversed":false,"date":[2026,7,31]}
                """);

        post(TOKEN, "SAVINGSACCOUNT", "WITHDRAWAL");

        assertThat(listener.received).hasSize(1);
        assertThat(listener.received.get(0).direction()).isEqualTo(TransactionDirection.DEBIT);
    }

    @Test
    void ourOwnLedgerRefRidesThroughForDedupDownstream() {
        stubTransaction("""
                {"id":42,"externalId":"a-ledger-ref-from-us","entryType":"CREDIT",
                 "transactionType":{"id":1,"value":"Deposit","deposit":true,"withdrawal":false},
                 "amount":25.000000,"reversed":false,"date":[2026,7,31]}
                """);

        post(TOKEN, "SAVINGSACCOUNT", "DEPOSIT");

        assertThat(listener.received.get(0).externalRef()).isEqualTo("a-ledger-ref-from-us");
    }

    /** Wrong token and disabled webhook are the SAME 404 — the endpoint must not confirm it exists. */
    @Test
    void theWrongTokenIsANotFoundAndNothingIsReadOrForwarded() {
        ResponseEntity<Void> response = post("wrong-token", "SAVINGSACCOUNT", "DEPOSIT");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(listener.received).isEmpty();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/10")));
    }

    @Test
    void aBlankTokenConfigurationDisablesTheEndpointEntirely() {
        FineractProperties disabled = FineractContractTestSupport.propertiesWithCoreEventsToken(
                wireMock.port(), " ");
        FineractCoreEventController off = new FineractCoreEventController(
                FineractContractTestSupport.client(disabled), disabled, listener);

        ResponseEntity<Void> response = off.onEvent(TOKEN, "SAVINGSACCOUNT", "DEPOSIT",
                new FineractCoreEventController.HookEnvelope(
                        new FineractCoreEventController.HookResponse(10L, 42L), null));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(listener.received).isEmpty();
    }

    @Test
    void unrelatedEntitiesAndActionsAreIgnoredWithoutAnyCoreRead() {
        post(TOKEN, "CLIENT", "CREATE");
        post(TOKEN, "SAVINGSACCOUNT", "APPROVE");

        assertThat(listener.received).isEmpty();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/10")));
    }

    @Test
    void aReversedTransactionIsNotAnnounced() {
        stubTransaction("""
                {"id":42,"externalId":"","entryType":"CREDIT",
                 "transactionType":{"id":1,"value":"Deposit","deposit":true,"withdrawal":false},
                 "amount":600.000000,"reversed":true,"date":[2026,7,31]}
                """);

        post(TOKEN, "SAVINGSACCOUNT", "DEPOSIT");

        assertThat(listener.received).isEmpty();
    }

    @Test
    void anAccountWithoutAnExternalIdIsIgnoredNotAnnounced() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/10"))
                .willReturn(okJson("{\"id\":10,\"externalId\":null,\"status\":{\"active\":true}}")));

        post(TOKEN, "SAVINGSACCOUNT", "DEPOSIT");

        assertThat(listener.received).isEmpty();
    }

    /**
     * The never-throw posture: a hook failure must never look like a posting
     * failure. Fineract's dispatcher ignores the response body; what matters
     * is that we answer 200 and don't propagate.
     */
    @Test
    void aFineractReadFailureIsSwallowedAndAnsweredOk() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/10"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatCode(() -> {
            ResponseEntity<Void> response = post(TOKEN, "SAVINGSACCOUNT", "DEPOSIT");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }).doesNotThrowAnyException();
        assertThat(listener.received).isEmpty();
    }

    @Test
    void aBodylessOrIdLessEventIsIgnored() {
        assertThat(controller.onEvent(TOKEN, "SAVINGSACCOUNT", "DEPOSIT", null)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(controller.onEvent(TOKEN, "SAVINGSACCOUNT", "DEPOSIT",
                new FineractCoreEventController.HookEnvelope(
                        new FineractCoreEventController.HookResponse(null, null), null))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(listener.received).isEmpty();
    }

    /**
     * ROUTE-level proof, through MockMvc — every other test calls the handler
     * method directly, which is how a mapping gap survived to production:
     * Fineract's Retrofit client requires the Payload URL to end in "/" and
     * then POSTs that slashed form, which Spring no longer implicitly matches
     * to a slash-less mapping. Both spellings must route.
     */
    @org.junit.jupiter.api.Test
    void bothUrlSpellingsRouteToTheHandler() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String body = "{\"response\":{\"savingsId\":10,\"resourceId\":42}}";

        // The slashed form is what Fineract actually sends.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/core-events/fineract/" + TOKEN + "/")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/core-events/fineract/" + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // A wrong token still 404s on both spellings.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/core-events/fineract/nope/")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}
