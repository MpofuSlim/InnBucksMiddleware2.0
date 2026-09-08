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
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire contract for the operator gateway. The load-bearing assertions are the
 * OUTBOUND ones: every call must carry the OPERATOR'S presented Authorization
 * header — never this middleware's AppUser credentials, whose absence is the
 * entire security model of the console statement endpoints.
 *
 * <p>Response stubs match what the fork actually writes, per endpoint —
 * Fineract has two JSON writers and these endpoints use both:
 * <ul>
 *   <li>{@code GET /v1/savingsaccounts/{id}} returns {@code SavingsAccountData}
 *       through the Jersey JACKSON writer (getters, NON_NULL; the class
 *       carries {@code @JsonLocalDateArrayFormat} so dates are arrays;
 *       {@code ExternalIdJsonConverter} writes a bare string or null;
 *       {@code depositType} is an EnumOptionData {id, code, value}).</li>
 *   <li>{@code GET /v1/loans/{id}} returns a String from the legacy GSON
 *       serializer ({@code LoanAccountData} FIELDS, dates as [y,m,d]
 *       arrays, ExternalIdAdapter bare string or dropped key).</li>
 *   <li>{@code GET /v1/loans/{id}/transactions} returns Spring's
 *       {@code Page<LoanTransactionData>} through the JACKSON writer:
 *       {@code totalElements}/{@code content}, ISO-string dates (no array
 *       annotation on that DTO), {@code type} an enum-data object.</li>
 * </ul>
 * Read out of the fork ({@code JerseyJacksonConverterConfig},
 * {@code JerseyJacksonObjectArgumentHandler}, {@code LoanTransactionMapper},
 * {@code LoanTransactionsApiResource}) — say so, per the house rule — and
 * since <b>CORROBORATED end-to-end against the ZW cell (2026-09-08)</b>:
 * seven live loans statemented, and every shape above parsed. The Gson loan
 * read's {@code [y,m,d]} dates produced populated
 * {@code disbursedOn}/{@code maturityDate}/{@code term}; the Jackson
 * transactions page produced dated lines with a non-null principal balance,
 * and its {@code NON_NULL} inclusion really does DROP an unallocated
 * portion (a live repayment came back with fee/penalty ABSENT, not zero).
 *
 * <p>To be precise about provenance: these stub bodies are still WRITTEN from
 * the serializers, not captured off the wire — what the cell verified is that
 * the resulting mapping is correct, not that these exact bytes were seen. A
 * capture would be strictly better; the balance invariants in the deploy
 * notes are the evidence in the meantime.
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

    private static String appUserBasic() {
        return FineractContractTestSupport.basicAuth(
                FineractContractTestSupport.READ_USER, FineractContractTestSupport.READ_PASS);
    }

    private static void stubJson(String path, int status, String body) {
        wireMock.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    // ---------------------------------------------------------------- deposit accounts

    @Test
    void forwardsTheOperatorsCredentialAndParsesTheAccount() {
        stubJson("/v1/savingsaccounts/17", 200, """
                {"id":17,"accountNo":"000000017",
                 "externalId":"3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet",
                 "clientId":42,"clientName":"Shumba Traders",
                 "depositType":{"id":100,"code":"depositAccountType.savingsDeposit","value":"Savings"},
                 "currency":{"code":"USD","name":"US Dollar","decimalPlaces":2},
                 "status":{"id":300,"code":"savingsAccountStatusType.active"}}
                """);
        stubJson("/v1/clients/42", 200, """
                {"id":42,"displayName":"Shumba Traders","mobileNo":"0771234567"}
                """);

        OperatorAccountView view = gateway.authorizeAndDescribeAccount(operator(), "17");

        assertThat(view.accountExternalId()).isEqualTo("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet");
        assertThat(view.kind()).isEqualTo(DepositAccountKind.SAVINGS);
        assertThat(view.currencyCode()).isEqualTo("USD");
        assertThat(view.accountNumber()).isEqualTo("000000017");
        assertThat(view.holderName()).isEqualTo("Shumba Traders");
        assertThat(view.holderMobile()).isEqualTo("0771234567");

        // The operator's header rides BOTH calls, and our AppUser Basic
        // (READ_USER/READ_PASS) appears on NEITHER.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/17"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC))
                .withHeader("Fineract-Platform-TenantId", equalTo(FineractContractTestSupport.TENANT)));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/clients/42"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC)));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/savingsaccounts/17"))
                .withHeader("Authorization", equalTo(appUserBasic())));
    }

    /**
     * Fixed and recurring deposits live in the same {@code m_savings_account}
     * table and resolve through the same read — {@code retrieveOne} filters on
     * id alone, no {@code deposit_type_enum} clause — so the console's "no
     * statement for fixed deposits" gap is a missing button, not a missing
     * endpoint. What the gateway adds is the KIND, so the document can be
     * titled honestly.
     */
    @Test
    void fixedAndRecurringDepositsResolveThroughTheSameReadWithTheirKind() {
        stubJson("/v1/savingsaccounts/3", 200, """
                {"id":3,"accountNo":"000000003","clientId":5,"clientName":"Chipo Ndlovu",
                 "depositType":{"id":200,"code":"depositAccountType.fixedDeposit","value":"Fixed Deposit"},
                 "currency":{"code":"USD"}}
                """);
        stubJson("/v1/savingsaccounts/4", 200, """
                {"id":4,"accountNo":"000000004","clientId":5,"clientName":"Chipo Ndlovu",
                 "depositType":{"id":300,"code":"depositAccountType.recurringDeposit","value":"Recurring Deposit"},
                 "currency":{"code":"USD"}}
                """);
        stubJson("/v1/clients/5", 200, "{\"id\":5,\"displayName\":\"Chipo Ndlovu\"}");

        assertThat(gateway.authorizeAndDescribeAccount(operator(), "3").kind())
                .isEqualTo(DepositAccountKind.FIXED_DEPOSIT);
        assertThat(gateway.authorizeAndDescribeAccount(operator(), "4").kind())
                .isEqualTo(DepositAccountKind.RECURRING_DEPOSIT);
    }

    @Test
    void accountWithoutExternalIdComesBackNullNotBlank() {
        // ExternalIdJsonConverter writes null for an empty id (and Gson's
        // adapter drops the key) — a branch-created account looks exactly
        // like this. No depositType at all is an unknown KIND, not a refusal.
        stubJson("/v1/savingsaccounts/23", 200, """
                {"id":23,"accountNo":"000000023","externalId":null,"clientId":7,
                 "clientName":"Walk In",
                 "currency":{"code":"USD"}}
                """);
        stubJson("/v1/clients/7", 200, "{\"id\":7,\"displayName\":\"Walk In\"}");

        OperatorAccountView view = gateway.authorizeAndDescribeAccount(operator(), "23");

        assertThat(view.accountExternalId()).isNull();
        assertThat(view.holderMobile()).isNull();
        assertThat(view.kind()).isEqualTo(DepositAccountKind.OTHER);
    }

    @Test
    void rejectedCredentialIsAnAuthFailure() {
        stubJson("/v1/savingsaccounts/17", 401, "{\"error\":\"Unauthorized\"}");

        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "17"))
                .isInstanceOf(CoreAuthException.class);
    }

    @Test
    void forbiddenAndMissingAccountsMapIdentically() {
        stubJson("/v1/savingsaccounts/17", 403,
                "{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.not.authorized\"}]}");
        stubJson("/v1/savingsaccounts/999", 404,
                "{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.saving.account.id.invalid\"}]}");

        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "17"))
                .isInstanceOf(CoreClientException.class);
        assertThatThrownBy(() -> gateway.authorizeAndDescribeAccount(operator(), "999"))
                .isInstanceOf(CoreClientException.class);
    }

    @Test
    void clientReadFailureCostsTheMobileNotTheStatement() {
        stubJson("/v1/savingsaccounts/17", 200, """
                {"id":17,"accountNo":"000000017","externalId":"x:wallet",
                 "clientId":42,"clientName":"Shumba Traders",
                 "depositType":{"id":100,"code":"depositAccountType.savingsDeposit","value":"Savings"},
                 "currency":{"code":"USD"}}
                """);
        stubJson("/v1/clients/42", 403, "{\"error\":\"no READ_CLIENT\"}");

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
        assertThatThrownBy(() -> dead.authorizeAndDescribeLoan(operator(), "9"))
                .isInstanceOf(CoreTransientException.class);
        assertThatThrownBy(() -> dead.listLoanTransactions(operator(), "9", 0, 50))
                .isInstanceOf(CoreTransientException.class);
    }

    // ---------------------------------------------------------------- loans

    /** LoanAccountData as the legacy Gson serializer writes it: fields, array dates. */
    private static final String ACTIVE_LOAN = """
            {"id":9,"accountNo":"000000009","externalId":"biz-loan-77","status":{"id":300,
              "code":"loanStatusType.active","value":"Active","pendingApproval":false,
              "waitingForDisbursal":false,"active":true,"closedObligationsMet":false,
              "closedWrittenOff":false,"closedRescheduled":false,"closed":false,"overpaid":false},
             "clientId":42,"clientName":"Shumba Traders","loanProductId":2,
             "loanProductName":"SME Working Capital",
             "currency":{"code":"USD","name":"US Dollar","decimalPlaces":2,"displaySymbol":"$"},
             "principal":5000.00,"approvedPrincipal":5000.00,"proposedPrincipal":6000.00,
             "termFrequency":12,"termPeriodFrequencyType":{"id":2,"code":"termFrequency.periodFrequencyType.months","value":"Months"},
             "numberOfRepayments":12,"repaymentEvery":1,
             "repaymentFrequencyType":{"id":2,"code":"repaymentFrequency.periodFrequencyType.months","value":"Months"},
             "annualInterestRate":24.000000,
             "timeline":{"submittedOnDate":[2026,2,20],"approvedOnDate":[2026,2,25],
               "expectedDisbursementDate":[2026,3,1],"actualDisbursementDate":[2026,3,1],
               "expectedMaturityDate":[2027,3,1]},
             "summary":{"currency":{"code":"USD"},"principalDisbursed":5000.00,"principalPaid":1250.00,
               "principalWrittenOff":0,"principalOutstanding":3750.00,"principalOverdue":416.67,
               "interestCharged":660.00,"interestPaid":270.00,"interestWaived":10.00,
               "interestOutstanding":380.00,"interestOverdue":55.00,
               "feeChargesCharged":50.00,"feeChargesPaid":50.00,"feeChargesOutstanding":0,
               "penaltyChargesCharged":15.00,"penaltyChargesPaid":0,"penaltyChargesOutstanding":15.00,
               "totalExpectedRepayment":5725.00,"totalRepayment":1570.00,
               "totalOutstanding":4145.00,"totalOverdue":486.67,"overdueSinceDate":[2026,8,1]},
             "inArrears":true}
            """;

    @Test
    void forwardsTheOperatorsCredentialAndParsesTheLoan() {
        stubJson("/v1/loans/9", 200, ACTIVE_LOAN);
        stubJson("/v1/clients/42", 200, "{\"id\":42,\"displayName\":\"Shumba Traders\",\"mobileNo\":\"0771234567\"}");

        OperatorLoanView loan = gateway.authorizeAndDescribeLoan(operator(), "9");

        assertThat(loan.loanId()).isEqualTo("9");
        assertThat(loan.accountNumber()).isEqualTo("000000009");
        assertThat(loan.externalId()).isEqualTo("biz-loan-77");
        assertThat(loan.currencyCode()).isEqualTo("USD");
        assertThat(loan.productName()).isEqualTo("SME Working Capital");
        assertThat(loan.status()).isEqualTo("Active");
        assertThat(loan.active()).isTrue();
        assertThat(loan.principal().amount()).isEqualTo(500_000);
        assertThat(loan.annualInterestRate()).isEqualByComparingTo(new BigDecimal("24.000000"));
        assertThat(loan.termDescription()).isEqualTo("12 monthly repayments");
        assertThat(loan.disbursedOn()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(loan.maturityDate()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(loan.borrowerName()).isEqualTo("Shumba Traders");
        assertThat(loan.borrowerMobile()).isEqualTo("0771234567");
        assertThat(loan.position()).isNotNull();
        assertThat(loan.position().principalOutstandingMinor()).isEqualTo(375_000);
        assertThat(loan.position().interestOutstandingMinor()).isEqualTo(38_000);
        assertThat(loan.position().feesOutstandingMinor()).isZero();
        assertThat(loan.position().penaltiesOutstandingMinor()).isEqualTo(1_500);
        assertThat(loan.position().totalOutstandingMinor()).isEqualTo(414_500);
        assertThat(loan.position().totalOverdueMinor()).isEqualTo(48_667);
        assertThat(loan.position().overdueSince()).isEqualTo(LocalDate.of(2026, 8, 1));

        // No `associations` on the wire: the resource reads the raw query
        // string, and an absent key fetches the loan alone.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/loans/9"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC))
                .withHeader("Fineract-Platform-TenantId", equalTo(FineractContractTestSupport.TENANT))
                .withoutQueryParam("associations"));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/clients/42"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC)));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/loans/9"))
                .withHeader("Authorization", equalTo(appUserBasic())));
    }

    @Test
    void anUndisbursedLoanHasNoPositionAndNoDates() {
        stubJson("/v1/loans/10", 200, """
                {"id":10,"accountNo":"000000010","status":{"id":200,"code":"loanStatusType.approved",
                  "value":"Approved","active":false},"clientId":42,"clientName":"Shumba Traders",
                 "loanProductName":"SME Working Capital","currency":{"code":"USD"},
                 "principal":5000.00,"numberOfRepayments":24,"repaymentEvery":2,
                 "repaymentFrequencyType":{"id":1,"code":"repaymentFrequency.periodFrequencyType.weeks","value":"Weeks"},
                 "timeline":{"submittedOnDate":[2026,2,20],"approvedOnDate":[2026,2,25],
                   "expectedDisbursementDate":[2026,3,1]},
                 "summary":{"principalOutstanding":0,"totalOutstanding":0}}
                """);
        stubJson("/v1/clients/42", 200, "{\"id\":42,\"displayName\":\"Shumba Traders\"}");

        OperatorLoanView loan = gateway.authorizeAndDescribeLoan(operator(), "10");

        assertThat(loan.active()).isFalse();
        assertThat(loan.status()).isEqualTo("Approved");
        assertThat(loan.disbursedOn()).isNull();
        assertThat(loan.maturityDate()).isNull();
        assertThat(loan.position()).isNull();
        assertThat(loan.externalId()).isNull();
        assertThat(loan.termDescription()).isEqualTo("24 repayments every 2 weeks");
    }

    /**
     * The paged endpoint as the JACKSON writer renders it. Five rows chosen
     * to pin every mapping rule: a disbursement (principal rises by the
     * AMOUNT), a repayment (falls by the principal PORTION), an interest
     * waiver (amount shown, no principal effect), a manually reversed
     * repayment (Fineract nulls its portions and balance; it must map
     * reversed with zero effect and no balance), and a charge-off row that
     * is a classification, not a movement.
     */
    @Test
    void loanTransactionsRideTheOperatorCredentialAndMapEveryKind() {
        stubJson("/v1/loans/9/transactions", 200, """
                {"content":[
                  {"id":905,"loanId":9,"type":{"id":27,"code":"loanTransactionType.chargeOff","value":"Charge-off",
                     "chargeoff":true,"repaymentType":false},
                   "date":"2026-08-20","currency":{"code":"USD","decimalPlaces":2},
                   "amount":3750.00,"principalPortion":3750.00,"interestPortion":380.00,
                   "feeChargesPortion":0,"penaltyChargesPortion":15.00,"outstandingLoanBalance":3750.00,
                   "manuallyReversed":false},
                  {"id":904,"loanId":9,"type":{"id":2,"code":"loanTransactionType.repayment","value":"Repayment",
                     "repayment":true,"repaymentType":true},
                   "date":"2026-08-10","currency":{"code":"USD","decimalPlaces":2},
                   "amount":475.00,"manuallyReversed":true,"reversedOnDate":"2026-08-11",
                   "reversalExternalId":"rev-904"},
                  {"id":903,"loanId":9,"type":{"id":4,"code":"loanTransactionType.waiver","value":"Waive interest",
                     "waiveInterest":true,"repaymentType":false},
                   "date":"2026-08-05","currency":{"code":"USD","decimalPlaces":2},
                   "amount":10.00,"principalPortion":0,"interestPortion":10.00,
                   "feeChargesPortion":0,"penaltyChargesPortion":0,"outstandingLoanBalance":3750.00,
                   "manuallyReversed":false},
                  {"id":902,"loanId":9,"type":{"id":2,"code":"loanTransactionType.repayment","value":"Repayment",
                     "repayment":true,"repaymentType":true},
                   "date":"2026-08-01","externalId":"rcpt-4411","currency":{"code":"USD","decimalPlaces":2},
                   "amount":475.00,"principalPortion":416.67,"interestPortion":55.00,
                   "feeChargesPortion":3.33,"penaltyChargesPortion":0,"outstandingLoanBalance":3750.00,
                   "manuallyReversed":false},
                  {"id":901,"loanId":9,"type":{"id":1,"code":"loanTransactionType.disbursement","value":"Disbursement",
                     "disbursement":true,"repaymentType":false},
                   "date":"2026-03-01","currency":{"code":"USD","decimalPlaces":2},
                   "amount":5000.00,"netDisbursalAmount":4950.00,"outstandingLoanBalance":5000.00,
                   "manuallyReversed":false}
                 ],
                 "pageable":{"pageNumber":0,"pageSize":50,"sort":{"sorted":true}},
                 "totalElements":5,"totalPages":1,"last":true,"size":50,"number":0,
                 "numberOfElements":5,"first":true,"empty":false}
                """);

        LoanTransactionPage page = gateway.listLoanTransactions(operator(), "9", 0, 50);

        assertThat(page.totalCount()).isEqualTo(5);
        assertThat(page.entries()).hasSize(5);

        LoanTransactionEntry chargeOff = page.entries().get(0);
        assertThat(chargeOff.kind()).isEqualTo(LoanEntryKind.NONE);
        assertThat(chargeOff.principalDeltaMinor()).isZero();
        assertThat(chargeOff.narrative()).isEqualTo("Charge-off");
        assertThat(chargeOff.principalBalanceAfter()).isEqualTo(375_000L);

        LoanTransactionEntry reversed = page.entries().get(1);
        assertThat(reversed.reversed()).isTrue();
        assertThat(reversed.principalDeltaMinor()).isZero();
        assertThat(reversed.principalBalanceAfter()).isNull();
        assertThat(reversed.principalPortion()).isNull();
        assertThat(reversed.amount().amount()).isEqualTo(47_500);

        LoanTransactionEntry waiver = page.entries().get(2);
        assertThat(waiver.kind()).isEqualTo(LoanEntryKind.WAIVER);
        assertThat(waiver.principalDeltaMinor()).isZero();
        assertThat(waiver.interestPortion().amount()).isEqualTo(1_000);

        LoanTransactionEntry repayment = page.entries().get(3);
        assertThat(repayment.coreId()).isEqualTo("902");
        assertThat(repayment.externalRef()).isEqualTo("rcpt-4411");
        assertThat(repayment.valueDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(repayment.kind()).isEqualTo(LoanEntryKind.REPAYMENT);
        assertThat(repayment.amount().amount()).isEqualTo(47_500);
        assertThat(repayment.principalPortion().amount()).isEqualTo(41_667);
        assertThat(repayment.interestPortion().amount()).isEqualTo(5_500);
        assertThat(repayment.feePortion().amount()).isEqualTo(333);
        assertThat(repayment.penaltyPortion().amount()).isZero();
        // Falls by the principal PORTION, not the amount.
        assertThat(repayment.principalDeltaMinor()).isEqualTo(-41_667);
        assertThat(repayment.principalBalanceAfter()).isEqualTo(375_000L);
        assertThat(repayment.reversed()).isFalse();

        LoanTransactionEntry disbursement = page.entries().get(4);
        assertThat(disbursement.kind()).isEqualTo(LoanEntryKind.DISBURSEMENT);
        assertThat(disbursement.principalDeltaMinor()).isEqualTo(500_000);
        assertThat(disbursement.principalPortion()).isNull();
        assertThat(disbursement.principalBalanceAfter()).isEqualTo(500_000L);

        // Newest first with an id tiebreak, page/size as asked, accruals
        // excluded at the wire — and the OPERATOR'S header, never ours.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/v1/loans/9/transactions"))
                .withHeader("Authorization", equalTo(OPERATOR_BASIC))
                .withQueryParam("page", equalTo("0"))
                .withQueryParam("size", equalTo("50"))
                .withQueryParam("sort", havingExactly("dateOf,desc", "id,desc"))
                .withQueryParam("excludedTypes", havingExactly("accrual", "accrualActivity", "accrualAdjustment")));
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/loans/9/transactions"))
                .withHeader("Authorization", equalTo(appUserBasic())));
    }

    @Test
    void loanRefundsAndCapitalisedIncomeRaiseThePrincipalAndArrayDatesAreAcceptedToo() {
        // The Gson spelling of the same page (`total`, array dates) must not
        // break the statement if this endpoint is ever routed through the
        // legacy serializer like the savings search is.
        stubJson("/v1/loans/9/transactions", 200, """
                {"total":3,"content":[
                  {"id":920,"type":{"id":35,"code":"loanTransactionType.capitalizedIncome","value":"Capitalized Income"},
                   "date":[2026,8,3],"currency":{"code":"USD"},"amount":100.00,"outstandingLoanBalance":3850.00,
                   "manuallyReversed":false},
                  {"id":919,"type":{"id":18,"code":"loanTransactionType.refund","value":"Refund"},
                   "date":[2026,8,2],"currency":{"code":"USD"},"amount":200.00,"principalPortion":200.00,
                   "outstandingLoanBalance":3750.00,"manuallyReversed":false},
                  {"id":918,"type":{"id":6,"code":"loanTransactionType.writeOff","value":"Close (as written-off)"},
                   "date":[2026,8,1],"currency":{"code":"USD"},"amount":3550.00,"principalPortion":3550.00,
                   "interestPortion":0,"outstandingLoanBalance":0,"manuallyReversed":false}
                 ]}
                """);

        LoanTransactionPage page = gateway.listLoanTransactions(operator(), "9", 0, 50);

        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.entries().get(0).kind()).isEqualTo(LoanEntryKind.OTHER);
        assertThat(page.entries().get(0).principalDeltaMinor()).isEqualTo(10_000);
        assertThat(page.entries().get(0).valueDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(page.entries().get(1).kind()).isEqualTo(LoanEntryKind.OTHER);
        assertThat(page.entries().get(1).principalDeltaMinor()).isEqualTo(20_000);
        assertThat(page.entries().get(2).kind()).isEqualTo(LoanEntryKind.WRITE_OFF);
        assertThat(page.entries().get(2).principalDeltaMinor()).isEqualTo(-355_000);
        assertThat(page.entries().get(2).principalBalanceAfter()).isZero();
    }

    @Test
    void loanRejectionsMapLikeAccountRejections() {
        stubJson("/v1/loans/9", 401, "{\"error\":\"Unauthorized\"}");
        stubJson("/v1/loans/9/transactions", 401, "{\"error\":\"Unauthorized\"}");
        stubJson("/v1/loans/999", 404,
                "{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.loan.id.invalid\"}]}");
        stubJson("/v1/loans/998", 403,
                "{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.not.authorized\"}]}");
        stubJson("/v1/loans/998/transactions", 403,
                "{\"errors\":[{\"userMessageGlobalisationCode\":\"error.msg.not.authorized\"}]}");

        assertThatThrownBy(() -> gateway.authorizeAndDescribeLoan(operator(), "9"))
                .isInstanceOf(CoreAuthException.class);
        assertThatThrownBy(() -> gateway.listLoanTransactions(operator(), "9", 0, 50))
                .isInstanceOf(CoreAuthException.class);
        assertThatThrownBy(() -> gateway.authorizeAndDescribeLoan(operator(), "999"))
                .isInstanceOf(CoreClientException.class);
        assertThatThrownBy(() -> gateway.authorizeAndDescribeLoan(operator(), "998"))
                .isInstanceOf(CoreClientException.class);
        assertThatThrownBy(() -> gateway.listLoanTransactions(operator(), "998", 0, 50))
                .isInstanceOf(CoreClientException.class);
    }
}
