package zw.co.innbucks.middleware.fineract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GET /v1/clients/external-id/{id}/accounts} — the listing that
 * {@code listDepositAccountRefs} answers ownership from in ONE call, where
 * {@code listDepositAccounts} pays 1 + N.
 *
 * <p><b>Provenance of these stubs.</b> Not invented, and not copied from the
 * Fineract docs. The payload below is transcribed from the fork's own
 * serialisation path: {@code ClientsApiResource.retrieveAssociatedAccounts}
 * returns {@code AccountSummaryCollectionData}, whose {@code savingsAccounts}
 * are {@code SavingsAccountSummaryData} instances built by
 * {@code AccountDetailsReadPlatformServiceJpaRepositoryImpl.SavingsAccountSummaryDataMapper}
 * — and Gson serialises that class's FIELDS. The fields this adapter consumes
 * ({@code externalId}, {@code accountNo}, {@code productName},
 * {@code currency.code}) all come from plain non-null column reads there.
 *
 * <p><b>Why balances are absent from these stubs even though the real endpoint
 * has those fields.</b> That mapper reads them with
 * {@code JdbcSupport.getBigDecimalDefaultToNullIfZero}, so a ZERO balance
 * becomes null — and Gson drops nulls, so the key vanishes. An empty wallet is
 * therefore indistinguishable on the wire from a field that was never
 * populated, which is precisely why money must keep coming from the
 * per-account read and never from this listing. The zero-balance case below
 * pins that: the account is still listed, and nothing pretends to know its
 * balance.
 */
class FineractAccountListingContractTest {

    private static final String CUSTOMER = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f";
    private static final String ACCOUNTS_URL = "/v1/clients/external-id/" + CUSTOMER + "/accounts";

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

    @Test
    void oneCallAnswersOwnershipForEveryAccount() {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productId":1,"productName":"InnBucks Wallet","shortProductName":"IBW",
                   "status":{"id":300,"code":"savingsAccountStatusType.active","value":"Active",
                             "submittedAndPendingApproval":false,"approved":false,"active":true},
                   "currency":{"code":"KES","name":"Kenyan Shilling","decimalPlaces":2,
                               "displaySymbol":"KSh","nameCode":"currency.KES"},
                   "accountBalance":1250.00,"availableBalance":1250.00,
                   "accountType":{"id":1,"code":"accountType.individual","value":"Individual"},
                   "depositType":{"id":100,"code":"depositAccountType.savingsDeposit","value":"Savings"}},
                  {"id":11,"accountNo":"000000011","externalId":"%s:savings",
                   "productId":2,"productName":"InnBucks Savings","shortProductName":"IBS",
                   "currency":{"code":"KES","name":"Kenyan Shilling","decimalPlaces":2,
                               "displaySymbol":"KSh","nameCode":"currency.KES"},
                   "accountBalance":40000.00,"availableBalance":40000.00}
                ]}
                """.formatted(CUSTOMER, CUSTOMER))));

        List<DepositAccountRef> refs = adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER));

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).account().externalId()).isEqualTo(CUSTOMER + ":wallet");
        assertThat(refs.get(0).name()).isEqualTo("InnBucks Wallet");
        assertThat(refs.get(0).currencyCode()).isEqualTo("KES");
        assertThat(refs.get(0).accountNumber()).isEqualTo("000000010");
        assertThat(refs.get(1).account().externalId()).isEqualTo(CUSTOMER + ":savings");

        // THE POINT OF THE WHOLE SLICE: the listing alone. Two accounts used to
        // mean two additional per-account savings reads.
        wireMock.verify(1, getRequestedFor(urlEqualTo(ACCOUNTS_URL)));
        wireMock.verify(0, getRequestedFor(urlMatching("/v1/savingsaccounts/.*")));
    }

    @Test
    void aZeroBalanceAccountIsStillListed() {
        // Fineract omits accountBalance/availableBalance entirely at zero
        // (getBigDecimalDefaultToNullIfZero + Gson dropping nulls). Ownership
        // must not care — an empty wallet is still the customer's wallet, and
        // refusing to list it would lock them out of receiving money.
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet",
                   "currency":{"code":"KES","decimalPlaces":2}}
                ]}
                """.formatted(CUSTOMER))));

        List<DepositAccountRef> refs = adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER));

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).account().externalId()).isEqualTo(CUSTOMER + ":wallet");
    }

    @Test
    void anAccountWithoutAnExternalIdIsInvisibleToTheApp() {
        // Not middleware-managed: no stable ref, so nothing here can address
        // it. Same rule listDepositAccounts has always applied.
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":9,"accountNo":"000000009","productName":"Branch-opened Savings",
                   "currency":{"code":"KES"}},
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet","currency":{"code":"KES"}}
                ]}
                """.formatted(CUSTOMER))));

        List<DepositAccountRef> refs = adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER));

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).account().externalId()).isEqualTo(CUSTOMER + ":wallet");
    }

    @Test
    void aCustomerWithNoAccountsIsEmptyNotAnError() {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL))
                .willReturn(okJson("{\"savingsAccounts\":[]}")));

        assertThat(adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER))).isEmpty();
    }

    @Test
    void aClientWithNoSavingsAtAllOmitsTheKeyEntirely() {
        // Gson drops the null collection, so the key is simply absent for a
        // client who has only loans (or nothing).
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL))
                .willReturn(okJson("{\"loanAccounts\":[]}")));

        assertThat(adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER))).isEmpty();
    }

    /**
     * The guard that MUST survive dropping the per-account balance read: it
     * used to run inside getBalance, which this path no longer calls. A
     * wrong-currency account in a single-currency cell is a provisioning fault,
     * and this list feeds pre-write ownership checks — so it fails loudly here
     * exactly as it does on the balance path, rather than quietly listing an
     * account money could then move through.
     */
    @Test
    void aWrongCurrencyAccountFailsTheListingJustAsItFailsABalanceRead() {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet","currency":{"code":"USD"}}
                ]}
                """.formatted(CUSTOMER))));

        assertThatThrownBy(() -> adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER)))
                .isInstanceOf(CoreServerException.class)
                .hasMessageContaining("does not match the cell currency");
    }

    @Test
    void aMissingCurrencyIsTreatedAsWrongNotAsAcceptable() {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet"}
                ]}
                """.formatted(CUSTOMER))));

        assertThatThrownBy(() -> adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER)))
                .isInstanceOf(CoreServerException.class);
    }

    // ------------------------------------------------- the bank's block

    /**
     * {@code subStatus} EXACTLY as the fork serialises it on this listing:
     * {@code SavingsAccountSummaryDataMapper} reads {@code sa.sub_status_enum}
     * and passes it through {@code SavingsEnumerations.subStatus}, which builds
     * {@code SavingsAccountSubStatusEnumData(id, code, value, none, inactive,
     * dormant, escheat, block, blockCredit, blockDebit)} — primitive booleans,
     * so Gson writes all seven, in field order. The id/code pairs are
     * {@code SavingsAccountSubStatusEnum}'s (fineract-core .../savings/domain/).
     * Note NONE is built with a literal {@code none = true}; every other value
     * carries {@code none: false}.
     */
    private static String subStatus(long id, String code, String value, String trueFlag) {
        StringBuilder flags = new StringBuilder();
        for (String flag : new String[]{"none", "inactive", "dormant", "escheat", "block", "blockCredit", "blockDebit"}) {
            flags.append(",\"").append(flag).append("\":").append(flag.equals(trueFlag));
        }
        return "{\"id\":" + id + ",\"code\":\"SavingsAccountSubStatusEnum." + code + "\",\"value\":\""
                + value + "\"" + flags + "}";
    }

    private void walletWithSubStatus(String subStatusJson) {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productId":1,"productName":"InnBucks Wallet","shortProductName":"IBW",
                   "status":{"id":300,"code":"savingsAccountStatusType.active","value":"Active",
                             "submittedAndPendingApproval":false,"approved":false,"active":true},
                   "currency":{"code":"KES","name":"Kenyan Shilling","decimalPlaces":2,
                               "displaySymbol":"KSh","nameCode":"currency.KES"},
                   "accountType":{"id":1,"code":"accountType.individual","value":"Individual"},
                   "subStatus":%s,
                   "depositType":{"id":100,"code":"depositAccountType.savingsDeposit","value":"Savings"}}
                ]}
                """.formatted(CUSTOMER, subStatusJson))));
    }

    private AccountRestriction walletRestriction() {
        List<DepositAccountRef> refs = adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER));
        assertThat(refs).hasSize(1);
        return refs.get(0).restriction();
    }

    @Test
    void aFullBlockIsReportedFromTheListingAlone() {
        // BLOCK (400): staff froze the wallet in the console. The session check
        // and the payment path both read it from THIS one call.
        walletWithSubStatus(subStatus(400, "block", "Block", "block"));

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.FULLY_BLOCKED);
        wireMock.verify(0, getRequestedFor(urlMatching("/v1/savingsaccounts/.*")));
    }

    @Test
    void aDebitBlockIsReported() {
        walletWithSubStatus(subStatus(600, "blockDebit", "BlockDebit", "blockDebit"));

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.DEBITS_BLOCKED);
    }

    @Test
    void aCreditBlockIsReported() {
        walletWithSubStatus(subStatus(500, "blockCredit", "BlockCredit", "blockCredit"));

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.CREDITS_BLOCKED);
    }

    @Test
    void noSubStatusIsNoBlock() {
        walletWithSubStatus(subStatus(0, "none", "None", "none"));

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.NONE);
    }

    @Test
    void dormantAndInactiveBlockNothing() {
        // Sub-statuses Fineract never checks on a transaction
        // (validateFor{Account,Debit,Credit}Block test only 400/600/500).
        walletWithSubStatus(subStatus(200, "dormant", "Dormant", "dormant"));
        assertThat(walletRestriction()).isEqualTo(AccountRestriction.NONE);

        wireMock.resetAll();
        walletWithSubStatus(subStatus(100, "inactive", "Inactive", "inactive"));
        assertThat(walletRestriction()).isEqualTo(AccountRestriction.NONE);
    }

    @Test
    void theIdAloneStillReportsABlockIfTheFlagsAreEverDropped() {
        // Defensive: a writer that omitted the derived booleans must not turn a
        // frozen wallet into an unblocked one. Only the id is left here.
        walletWithSubStatus("{\"id\":400,\"code\":\"SavingsAccountSubStatusEnum.block\",\"value\":\"Block\"}");

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.FULLY_BLOCKED);
    }

    @Test
    void anAbsentSubStatusIsNoBlockNotAFailure() {
        // Every stub above this section omits the key — the listing still
        // answers, and the core keeps refusing whatever the block covers.
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet","currency":{"code":"KES"}}
                ]}
                """.formatted(CUSTOMER))));

        assertThat(walletRestriction()).isEqualTo(AccountRestriction.NONE);
    }

    @Test
    void theBalanceListingCarriesTheSameRestriction() {
        // listDepositAccounts (GET /me/accounts) reads the same summary, then
        // one balance read per account; the restriction comes from the summary.
        walletWithSubStatus(subStatus(600, "blockDebit", "BlockDebit", "blockDebit"));
        // The colon reaches the wire percent-encoded (CLAUDE.md, slice 3a).
        wireMock.stubFor(get(urlEqualTo("/v1/savingsaccounts/external-id/" + CUSTOMER + "%3Awallet"))
                .willReturn(okJson("""
                        {"id":10,"externalId":"%s:wallet","accountNo":"000000010","clientId":7,
                         "status":{"active":true},"currency":{"code":"KES"},
                         "summary":{"accountBalance":12.50,"availableBalance":12.50}}
                        """.formatted(CUSTOMER))));

        List<DepositAccountSummary> accounts = adapter.listDepositAccounts(new CoreCustomerRef(CUSTOMER));

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).restriction()).isEqualTo(AccountRestriction.DEBITS_BLOCKED);
    }

    @Test
    void theListingRidesTheREADCredentialAndTheTenantHeader() {
        wireMock.stubFor(get(urlEqualTo(ACCOUNTS_URL)).willReturn(okJson("""
                {"savingsAccounts":[
                  {"id":10,"accountNo":"000000010","externalId":"%s:wallet",
                   "productName":"InnBucks Wallet","currency":{"code":"KES"}}
                ]}
                """.formatted(CUSTOMER))));

        adapter.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER));

        wireMock.verify(getRequestedFor(urlEqualTo(ACCOUNTS_URL))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                        FineractContractTestSupport.basicAuth(
                                FineractContractTestSupport.READ_USER, FineractContractTestSupport.READ_PASS)))
                .withHeader("Fineract-Platform-TenantId",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(FineractContractTestSupport.TENANT)));
    }
}
