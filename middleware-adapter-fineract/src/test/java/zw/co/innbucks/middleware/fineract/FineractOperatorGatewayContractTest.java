package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire contract for the operator gateway. The load-bearing assertions are the
 * OUTBOUND ones: every call must carry the OPERATOR'S presented Authorization
 * header — never this middleware's AppUser credentials, whose absence is the
 * entire security model of the console statement endpoint.
 *
 * <p>Response stubs match the fork's Gson-over-fields serialisation:
 * SavingsAccountData fields (accountNo/externalId/clientId/clientName/currency,
 * fineract-core SavingsAccountData.java:57-72; ExternalIdAdapter emits a bare
 * string or drops the key), ClientData fields (displayName/mobileNo,
 * ClientData.java:66-67).
 */
class FineractOperatorGatewayContractTest {

    private static final String OPERATOR_BASIC = "Basic b3BlcmF0b3I6dGVsbGVyLXB3";

    static WireMockServer wireMock;
    static FineractOperatorGateway gateway;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        gateway = FineractContractTestSupport.operatorGateway(
                FineractContractTestSupport.properties(wireMock.port()));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    private static OperatorCredential operator() {
        return new OperatorCredential(OPERATOR_BASIC);
    }

    @Test
    void forwardsTheOperatorsCredentialAndParsesTheAccount() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/17"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":17,"accountNo":"000000017",
                                 "externalId":"3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet",
                                 "clientId":42,"clientName":"Shumba Traders",
                                 "currency":{"code":"USD","name":"US Dollar","decimalPlaces":2},
                                 "status":{"id":300,"code":"savingsAccountStatusType.active"}}
                                """)));
        wireMock.stubFor(get(urlPathEqualTo("/v1/clients/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":42,"displayName":"Shumba Traders","mobileNo":"0771234567"}
                                """)));

        OperatorAccountView view = gateway.authorizeAndDescribeAccount(operator(), "17");

        assertThat(view.accountExternalId()).isEqualTo("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet");
        assertThat(view.currencyCode()).isEqualTo("USD");
        assertThat(view.accountNumber()).isEqualTo("000000017");
        assertThat(view.holderName()).isEqualTo("Shumba Traders");
        assertThat(view.holderMobile()).isEqualTo("0771234567");

        // The operator's header rides BOTH calls, and our AppUser Basic
        // (READ_USER/READ_PASS) appears on NEITHER.
        String appUserBasic = FineractContractTestSupport.basicAuth(
                FineractContractTestSupport.READ_USER, FineractContractTestSupport.READ_PASS);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/17"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC))
                .withHeader("Fineract-Platform-TenantId", equalTo(FineractContractTestSupport.TENANT)));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/clients/42"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC)));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/17"))
                .withHeader("Authorization", equalTo(appUserBasic)));
    }

    @Test
    void accountWithoutExternalIdComesBackNullNotBlank() {
        // Gson's ExternalIdAdapter drops the key entirely for an empty id —
        // a branch-created account looks exactly like this.
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/23"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":23,"accountNo":"000000023","clientId":7,
                                 "clientName":"Walk In",
                                 "currency":{"code":"USD"}}
                                """)));
        wireMock.stubFor(get(urlPathEqualTo("/v1/clients/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"displayName\":\"Walk In\"}")));

        OperatorAccountView view = gateway.authorizeAndDescribeAccount(operator(), "23");

        assertThat(view.accountExternalId()).isNull();
        assertThat(view.holderMobile()).isNull();
    }

    @Test
    void rejectedCredentialIsAnAuthFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/17"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Unauthorized\"}")));

        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "17"))
                .isInstanceOf(CoreAuthException.class);
    }

    @Test
    void forbiddenAndMissingAccountsMapIdentically() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/17"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.not.authorized\"}]}")));
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/999"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.saving.account.id.invalid\"}]}")));

        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "17"))
                .isInstanceOf(CoreClientException.class);
        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "999"))
                .isInstanceOf(CoreClientException.class);
    }

    @Test
    void clientReadFailureCostsTheMobileNotTheStatement() {
        wireMock.stubFor(get(urlPathEqualTo("/v1/savingsaccounts/17"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":17,"accountNo":"000000017","externalId":"x:wallet",
                                 "clientId":42,"clientName":"Shumba Traders",
                                 "currency":{"code":"USD"}}
                                """)));
        wireMock.stubFor(get(urlPathEqualTo("/v1/clients/42"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"no READ_CLIENT\"}")));

        OperatorAccountView view = gateway.authorizeAndDescribeAccount(operator(), "17");

        assertThat(view.holderName()).isEqualTo("Shumba Traders");
        assertThat(view.holderMobile()).isNull();
    }

    @Test
    void connectRefusedIsTransientNotAnAnswer() {
        FineractOperatorGateway dead = FineractContractTestSupport.operatorGateway(
                FineractContractTestSupport.properties(1));

        assertThatThrownBy(() -> dead.authorizeAndDescribeAccount(operator(), "17"))
                .isInstanceOf(CoreTransientException.class);
    }
}
