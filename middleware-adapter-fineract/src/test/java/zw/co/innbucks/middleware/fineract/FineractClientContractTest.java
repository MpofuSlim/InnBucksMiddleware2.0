package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.ClientResponse;
import zw.co.innbucks.middleware.fineract.dto.FineractDtos.CommandResponse;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.READ_PASS;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.READ_USER;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.WRITE_PASS;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.WRITE_USER;
import static zw.co.innbucks.middleware.fineract.FineractContractTestSupport.basicAuth;

/**
 * Pins the WIRE contract with Fineract: outbound shapes (headers, auth split,
 * idempotency, locale/dateFormat), one test per observed response shape, and
 * the failure taxonomy per operation kind. Standalone WireMock, no Spring.
 */
class FineractClientContractTest {

    static WireMockServer wireMock;
    static FineractClient client;
    static FineractProperties properties;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        properties = FineractContractTestSupport.properties(wireMock.port());
        client = FineractContractTestSupport.client(properties);
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    // ------------------------------------------------------------- outbound

    @Test
    void createClientSendsTheFullOutboundContract() {
        wireMock.stubFor(post(urlEqualTo("/v1/clients"))
                .willReturn(okJson("{\"officeId\":1,\"clientId\":7,\"resourceId\":7}")));

        CommandResponse response = client.createClient(
                "cust-uuid-1", "Tariro", "Moyo", "+254712000001", "key-1");

        assertThat(response.resourceId()).isEqualTo(7L);
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/clients"))
                // Write ops MUST ride the write AppUser + tenant + idempotency key.
                .withHeader("Authorization", equalTo(basicAuth(WRITE_USER, WRITE_PASS)))
                .withHeader("Fineract-Platform-TenantId", equalTo("default"))
                .withHeader("Idempotency-Key", equalTo(FineractIdempotencyKey.forCore("key-1")))
                .withRequestBody(matchingJsonPath("$.officeId", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.externalId", equalTo("cust-uuid-1")))
                .withRequestBody(matchingJsonPath("$.firstname", equalTo("Tariro")))
                .withRequestBody(matchingJsonPath("$.mobileNo", equalTo("+254712000001")))
                .withRequestBody(matchingJsonPath("$.active", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.activationDate", equalTo("2026-07-30")))
                // Fineract rejects date-bearing bodies without these two.
                .withRequestBody(matchingJsonPath("$.locale", equalTo("en")))
                .withRequestBody(matchingJsonPath("$.dateFormat", equalTo("yyyy-MM-dd"))));
    }

    @Test
    void depositSendsAmountRefAndCommandOnTheExternalIdRoute() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .willReturn(okJson("{\"resourceId\":99,\"changes\":{\"transactionAmount\":25.50}}")));

        CommandResponse response = client.deposit("acct-1", new BigDecimal("25.50"), "ref-1", "key-2");

        assertThat(response.resourceId()).isEqualTo(99L);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .withQueryParam("command", equalTo("deposit"))
                // Compressed to fit Fineract's VARCHAR(50) column — never the raw key.
                .withHeader("Idempotency-Key", equalTo(FineractIdempotencyKey.forCore("key-2")))
                .withRequestBody(matchingJsonPath("$.transactionAmount", equalTo("25.5")))
                // The reconciliation handle is ALWAYS attached.
                .withRequestBody(matchingJsonPath("$.externalId", equalTo("ref-1")))
                .withRequestBody(matchingJsonPath("$.transactionDate", equalTo("2026-07-30")))
                // Fineract validates paymentTypeId notNull() on every savings
                // transaction — omitting it 400s the whole money movement.
                .withRequestBody(matchingJsonPath("$.paymentTypeId", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.locale", equalTo("en"))));
    }

    @Test
    void withdrawalCarriesThePaymentTypeToo() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .willReturn(okJson("{\"resourceId\":101,\"changes\":{\"transactionAmount\":5.00}}")));

        client.withdraw("acct-1", new BigDecimal("5.00"), "ref-w", "key-w");

        wireMock.verify(postRequestedFor(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .withQueryParam("command", equalTo("withdrawal"))
                .withRequestBody(matchingJsonPath("$.paymentTypeId", equalTo("7"))));
    }

    @Test
    void transferSendsBothLegsAsSavingsAccountType() {
        wireMock.stubFor(post(urlEqualTo("/v1/accounttransfers"))
                .willReturn(okJson("{\"resourceId\":55,\"savingsId\":11}")));

        client.transfer(7L, 11L, 8L, 12L, new BigDecimal("100.00"), "rent", "key-3");

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/accounttransfers"))
                .withHeader("Idempotency-Key", equalTo(FineractIdempotencyKey.forCore("key-3")))
                .withRequestBody(matchingJsonPath("$.fromAccountType", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.toAccountType", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.fromAccountId", equalTo("11")))
                .withRequestBody(matchingJsonPath("$.toAccountId", equalTo("12")))
                .withRequestBody(matchingJsonPath("$.transferAmount", equalTo("100.0")))
                .withRequestBody(matchingJsonPath("$.transferDate", equalTo("2026-07-30"))));
    }

    @Test
    void readsRideTheReadOnlyCredentialNeverTheWriteOne() {
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/cust-uuid-1"))
                .willReturn(okJson("{\"id\":7,\"externalId\":\"cust-uuid-1\",\"firstname\":\"Tariro\","
                        + "\"lastname\":\"Moyo\",\"active\":true}")));

        ClientResponse response = client.findClientByExternalId("cust-uuid-1");

        assertThat(response.id()).isEqualTo(7L);
        // The containment property: a leaked read credential cannot move money,
        // and reads must never touch the write credential.
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/clients/external-id/cust-uuid-1"))
                .withHeader("Authorization", equalTo(basicAuth(READ_USER, READ_PASS))));
    }

    // ------------------------------------------------------- response shapes

    @Test
    void notFoundOnAReadIsAPositiveNullNotAnException() {
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/missing"))
                .willReturn(aResponse().withStatus(404).withBody(
                        "{\"developerMessage\":\"The requested resource is not available.\"}")));

        assertThat(client.findClientByExternalId("missing")).isNull();
    }

    @Test
    void badCredentialsMapToCoreAuth() {
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/x"))
                .willReturn(aResponse().withStatus(401).withBody("{}")));

        assertThatThrownBy(() -> client.findClientByExternalId("x"))
                .isInstanceOf(CoreAuthException.class);
    }

    @Test
    void domainRuleVetoMapsToCoreClientWithTheGlobalisationCode() {
        // Fineract uses 403 for business-rule vetoes — the observed envelope.
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .willReturn(aResponse().withStatus(403).withBody("""
                        {"developerMessage":"Request was understood but caused a domain rule violation.",
                         "userMessageGlobalisationCode":"error.msg.savingsaccount.transaction.insufficient.account.balance",
                         "errors":[{"userMessageGlobalisationCode":"error.msg.savingsaccount.transaction.insufficient.account.balance",
                                    "defaultUserMessage":"Insufficient account balance."}]}
                        """)));

        assertThatThrownBy(() -> client.withdraw("acct-1", new BigDecimal("9.99"), "ref-x", "key-x"))
                .isInstanceOf(CoreClientException.class)
                .hasMessageContaining("insufficient.account.balance");
    }

    @Test
    void validationErrorEnvelopeMapsToCoreClient() {
        wireMock.stubFor(post(urlEqualTo("/v1/clients"))
                .willReturn(aResponse().withStatus(400).withBody("""
                        {"developerMessage":"The request was invalid.",
                         "errors":[{"userMessageGlobalisationCode":"validation.msg.client.externalId.exceeds.max.length",
                                    "defaultUserMessage":"The parameter externalId exceeds max length."}]}
                        """)));

        assertThatThrownBy(() -> client.createClient("x".repeat(200), "A", "B", "+254", "key-y"))
                .isInstanceOf(CoreClientException.class)
                .hasMessageContaining("exceeds.max.length");
    }

    @Test
    void underProcessing425OnAWriteParksAsUnknown() {
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .willReturn(aResponse().withStatus(425).withBody("{}")));

        assertThatThrownBy(() -> client.deposit("acct-1", new BigDecimal("10.00"), "ref-425", "key-425"))
                .isInstanceOf(CoreUnknownOutcomeException.class)
                .satisfies(ex -> assertThat(((CoreUnknownOutcomeException) ex).txRef().reference())
                        .isEqualTo("key-425"));
    }

    @Test
    void serverErrorOnAWriteParksAsUnknownNeverFailed() {
        // The command REACHED Fineract; rollback is likely but unprovable.
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/acct-1/transactions"))
                .willReturn(aResponse().withStatus(500).withBody("{\"developerMessage\":\"boom\"}")));

        assertThatThrownBy(() -> client.deposit("acct-1", new BigDecimal("10.00"), "ref-500", "key-500"))
                .isInstanceOf(CoreUnknownOutcomeException.class);
    }

    @Test
    void serverErrorOnAReadIsRetryableCoreServer() {
        wireMock.stubFor(get(urlEqualTo("/v1/clients/external-id/x"))
                .willReturn(aResponse().withStatus(503).withBody("{}")));

        assertThatThrownBy(() -> client.findClientByExternalId("x"))
                .isInstanceOf(CoreServerException.class);
    }

    @Test
    void connectRefusedIsTransientForReadsAndWritesAlike() {
        // A closed port guarantees the request never left — safe to call FAILED
        // even for a write. Separate client at a dead port; the shared WireMock
        // is never restarted (a second start would change its port).
        FineractProperties dead = FineractContractTestSupport.properties(1);
        FineractClient deadClient = FineractContractTestSupport.client(dead);

        assertThatThrownBy(() -> deadClient.findClientByExternalId("x"))
                .isInstanceOf(CoreTransientException.class);
        assertThatThrownBy(() -> deadClient.deposit("acct", new BigDecimal("1.00"), "r", "k"))
                .isInstanceOf(CoreTransientException.class);
    }
}
