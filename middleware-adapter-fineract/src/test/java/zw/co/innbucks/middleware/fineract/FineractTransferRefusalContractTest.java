package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A refused TRANSFER never carries Fineract's wording. Its message is all the
 * sender is ever shown — the 422 {@code core_rejected} detail, the ledger's
 * {@code failure_message} and every same-key replay repeat it — and Fineract's
 * sentences on this path name accounts by internal id and state, the
 * recipient's included. So the adapter answers with fixed copy: its own for
 * the refusals the app can act on, the generic decline for everything else.
 * Deposits and withdrawals touch only the caller's own account and keep
 * Fineract's wording (last case).
 *
 * <p><b>Provenance of the refusal bodies.</b> Transcribed from the fork's
 * serialisation path (MpofuSlim/fineract, develop), not observed on a cell:
 * the exception's mapper picks the envelope, {@code ApiGlobalErrorResponse}
 * builds it, and Jersey writes it with the Jackson converter configured
 * NON_NULL — so a null arg is {@code {}} and numbers are JSON numbers.
 * <ul>
 *   <li>403 {@code domainRuleViolation} ({@code PlatformDomainRuleExceptionMapper}):
 *       one {@code ApiParameterError.generalError}, parameterName "id", args =
 *       the exception's default-message args.</li>
 *   <li>404 {@code notFound} ({@code PlatformResourceNotFoundExceptionMapper}).</li>
 *   <li>400 {@code badClientRequest} ({@code PlatformApiDataValidationExceptionMapper})
 *       with the default "validation.msg.validation.errors.exist" envelope.</li>
 * </ul>
 * The two account reads are stubbed in the shape the balance tests use.
 */
class FineractTransferRefusalContractTest {

    private static final String FROM = "src-uuid:wallet";
    private static final String TO = "dst-uuid:wallet";

    static WireMockServer wireMock;
    static FineractAdapter adapter;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        FineractProperties properties = FineractContractTestSupport.properties(wireMock.port());
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

    /** Colons reach the wire percent-encoded (CLAUDE.md, slice 3a); stubs match that form. */
    private static String onWire(String externalId) {
        return externalId.replace(":", "%3A");
    }

    private static void savings(String externalId, long id, long clientId) {
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/" + onWire(externalId)))
                .willReturn(okJson("""
                        {"id":%d,"externalId":"%s","accountNo":"0000000%d","clientId":%d,
                         "status":{"active":true},"currency":{"code":"KES"},
                         "summary":{"accountBalance":40.00,"availableBalance":40.00}}
                        """.formatted(id, externalId, id, clientId))));
    }

    private static void bothSidesExist() {
        savings(FROM, 11, 7);
        savings(TO, 12, 8);
    }

    private static void transferAnswers(int status, String body) {
        wireMock.stubFor(post(urlEqualTo("/v1/accounttransfers"))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static TransferCommand transferOf(String amount) {
        return new TransferCommand(new AccountRef(FROM), new AccountRef(TO),
                MinorUnits.ofMajor(new java.math.BigDecimal(amount), "KES"), "rent", new TxRef("ref-t1"));
    }

    private static void transfer(String amount) {
        adapter.transfer(transferOf(amount), new IdempotencyKey("key-t1"));
    }

    /** {@code InsufficientAccountBalanceException("transactionAmount", balance, null, amount)}. */
    private static final String INSUFFICIENT_BALANCE = """
            {"developerMessage":"Request was understood but caused a domain rule violation.",
             "httpStatusCode":"403",
             "defaultUserMessage":"Errors contain reason for domain rule violation.",
             "userMessageGlobalisationCode":"validation.msg.domain.rule.violation",
             "errors":[{"developerMessage":"Insufficient account balance.",
                        "defaultUserMessage":"Insufficient account balance.",
                        "userMessageGlobalisationCode":"error.msg.savingsaccount.transaction.insufficient.account.balance",
                        "parameterName":"id",
                        "args":[{"value":"transactionAmount"},{"value":40.000000},{},{"value":100.00}]}]}
            """;

    @Test
    void insufficientBalanceIsTheOneSenderRefusalWithItsOwnCopy() {
        // Raised only by handleWithdrawal's balance check, i.e. on the SOURCE.
        bothSidesExist();
        transferAnswers(403, INSUFFICIENT_BALANCE);

        assertThatThrownBy(() -> transfer("100.00"))
                .isInstanceOf(CoreClientException.class)
                .isNotInstanceOf(CoreAccountRestrictedException.class)
                .hasMessage("There is not enough money in your account for this payment.");
    }

    @Test
    void differentCurrenciesReadsAsTheReceivingAccountsRefusalWithoutItsCurrency() {
        // DifferentCurrenciesException(from, to): thrown after both legs, about
        // the DESTINATION — whose currency the sender must not learn.
        bothSidesExist();
        transferAnswers(403, """
                {"developerMessage":"Request was understood but caused a domain rule violation.",
                 "httpStatusCode":"403",
                 "defaultUserMessage":"Errors contain reason for domain rule violation.",
                 "userMessageGlobalisationCode":"validation.msg.domain.rule.violation",
                 "errors":[{"developerMessage":"Trying to transfer from KES to USD",
                            "defaultUserMessage":"Trying to transfer from KES to USD",
                            "userMessageGlobalisationCode":"error.msg.accounttransfer.different.currencies",
                            "parameterName":"id",
                            "args":[]}]}
                """);

        assertThatThrownBy(() -> transfer("5.00"))
                .isInstanceOf(CoreClientException.class)
                .hasMessage("The receiving account cannot accept this payment.")
                .hasMessageNotContaining("USD");
    }

    @Test
    void anInactiveAccountOnEitherSideIsTheGenericDeclineNeverYourAccount() {
        // SavingsAccount.withdraw AND .deposit raise this with the same code,
        // sentence and args (the date): nothing says whose account it is.
        bothSidesExist();
        transferAnswers(400, """
                {"developerMessage":"The request was invalid. This typically will happen due to validation errors which are provided.",
                 "httpStatusCode":"400",
                 "defaultUserMessage":"Validation errors exist.",
                 "userMessageGlobalisationCode":"validation.msg.validation.errors.exist",
                 "errors":[{"developerMessage":"Transaction is not allowed. Account is not active.",
                            "defaultUserMessage":"Transaction is not allowed. Account is not active.",
                            "userMessageGlobalisationCode":"error.msg.savingsaccount.transaction.account.is.not.active",
                            "parameterName":"transactionDate",
                            "args":[{"value":"2026-07-30"}]}]}
                """);

        assertThatThrownBy(() -> transfer("5.00"))
                .isInstanceOf(CoreClientException.class)
                .hasMessage("The banking system refused this payment.");
    }

    @Test
    void anAccountFineractCannotFindMidTransferNamesNoAccountId() {
        // SavingsAccountNotFoundException(id) through notFound(): the sentence
        // is "Savings account with identifier 12 does not exist".
        bothSidesExist();
        transferAnswers(404, """
                {"developerMessage":"The requested resource is not available.",
                 "httpStatusCode":"404",
                 "defaultUserMessage":"The requested resource is not available.",
                 "userMessageGlobalisationCode":"error.msg.resource.not.found",
                 "errors":[{"developerMessage":"Savings account with identifier 12 does not exist",
                            "defaultUserMessage":"Savings account with identifier 12 does not exist",
                            "userMessageGlobalisationCode":"error.msg.saving.account.id.invalid",
                            "parameterName":"id",
                            "args":[{"value":12}]}]}
                """);

        assertThatThrownBy(() -> transfer("5.00"))
                .isInstanceOf(CoreClientException.class)
                .hasMessage("The banking system refused this payment.")
                .hasMessageNotContaining("12");
    }

    @Test
    void aBlockedRecipientKeepsItsFixedCopyAndABlockedSourceStaysARestriction() {
        // The restriction mapping runs first and is unchanged by the generic
        // rule — the two must never fall through to each other.
        bothSidesExist();
        transferAnswers(403, """
                {"developerMessage":"Request was understood but caused a domain rule violation.",
                 "httpStatusCode":"403",
                 "defaultUserMessage":"Errors contain reason for domain rule violation.",
                 "userMessageGlobalisationCode":"validation.msg.domain.rule.violation",
                 "errors":[{"developerMessage":"Any Credit transactions to 12 is not allowed, since the account is blocked for credits",
                            "defaultUserMessage":"Any Credit transactions to 12 is not allowed, since the account is blocked for credits",
                            "userMessageGlobalisationCode":"error.msg.savings.account.credit.transaction.not.allowed",
                            "parameterName":"id",
                            "args":[{"value":12}]}]}
                """);
        assertThatThrownBy(() -> transfer("5.00"))
                .isNotInstanceOf(CoreAccountRestrictedException.class)
                .hasMessage("The receiving account cannot accept this payment.");

        transferAnswers(403, """
                {"developerMessage":"Request was understood but caused a domain rule violation.",
                 "httpStatusCode":"403",
                 "defaultUserMessage":"Errors contain reason for domain rule violation.",
                 "userMessageGlobalisationCode":"validation.msg.domain.rule.violation",
                 "errors":[{"developerMessage":"Any debit transactions from 11 is not allowed, since the account is blocked for debits",
                            "defaultUserMessage":"Any debit transactions from 11 is not allowed, since the account is blocked for debits",
                            "userMessageGlobalisationCode":"error.msg.savings.account.debit.transaction.not.allowed",
                            "parameterName":"id",
                            "args":[{"value":11}]}]}
                """);
        assertThatThrownBy(() -> transfer("5.00"))
                .isInstanceOf(CoreAccountRestrictedException.class);
    }

    @Test
    void aRecipientFineractDoesNotKnowReadsLikeOneThatCannotReceiveAndNothingIsSent() {
        // The pre-write read 404s (a positive "no such externalId"). It used to
        // answer "No Fineract savings account with externalId <id>"; now it says
        // nothing a sender could probe account ids with — and no write goes out.
        savings(FROM, 11, 7);
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/" + onWire(TO)))
                .willReturn(aResponse().withStatus(404).withBody("""
                        {"developerMessage":"The requested resource is not available.",
                         "httpStatusCode":"404",
                         "defaultUserMessage":"The requested resource is not available.",
                         "userMessageGlobalisationCode":"error.msg.resource.not.found",
                         "errors":[{"developerMessage":"Savings account with external identifier dst-uuid:wallet does not exist",
                                    "defaultUserMessage":"Savings account with external identifier dst-uuid:wallet does not exist",
                                    "userMessageGlobalisationCode":"error.msg.saving.account.id.invalid",
                                    "parameterName":"id",
                                    "args":[{"value":"dst-uuid:wallet"}]}]}
                        """)));

        assertThatThrownBy(() -> transfer("5.00"))
                .isInstanceOf(CoreClientException.class)
                .hasMessage("The receiving account cannot accept this payment.")
                .hasMessageNotContaining("dst-uuid");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/accounttransfers")));
    }

    @Test
    void aTransferNotInTheCellsCurrencyIsRefusedInOurOwnWordsBeforeFineractIsAsked() {
        // The one transfer refusal that is not fixed copy: the adapter's own
        // sentence, naming only the two currencies (no account, nothing of
        // Fineract's), raised before any read or write. The transfer 422's
        // Swagger documents it as the fourth detail.
        bothSidesExist();

        assertThatThrownBy(() -> adapter.transfer(new TransferCommand(new AccountRef(FROM), new AccountRef(TO),
                        new MinorUnits(500L, "USD"), "rent", new TxRef("ref-t2")), new IdempotencyKey("key-t2")))
                .isInstanceOf(CoreClientException.class)
                .isNotInstanceOf(CoreAccountRestrictedException.class)
                .hasMessage("Amount currency USD does not match the cell currency KES");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aWithdrawalKeepsFineractsWordingBecauseItTouchesOnlyTheCallersAccount() {
        // Unchanged on purpose: the sentence is about the caller's own wallet.
        wireMock.stubFor(post(urlPathEqualTo("/v1/savingsaccounts/external-id/" + onWire(FROM) + "/transactions"))
                .withQueryParam("command", equalTo("withdrawal"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json").withBody(INSUFFICIENT_BALANCE)));

        assertThatThrownBy(() -> adapter.withdraw(new MoneyMovementCommand(new AccountRef(FROM),
                        new MinorUnits(10000L, "KES"), null, new TxRef("ref-w1")), new IdempotencyKey("key-w1")))
                .isInstanceOf(CoreClientException.class)
                .hasMessageContaining("error.msg.savingsaccount.transaction.insufficient.account.balance")
                .hasMessageContaining("Insufficient account balance.");
    }
}
