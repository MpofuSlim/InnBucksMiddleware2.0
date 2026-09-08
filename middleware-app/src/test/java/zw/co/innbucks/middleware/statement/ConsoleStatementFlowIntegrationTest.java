package zw.co.innbucks.middleware.statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;
import zw.co.innbucks.middleware.support.SettableOperatorPort;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console (bank-issued) statements over real HTTP wiring. What is pinned
 * here and nowhere else: Spring Security actually PERMITS the paths so the
 * controller's own Fineract-delegated auth runs (a 401 produced by OUR
 * problem body, not by the resource server), the operator's credential
 * reaches the port verbatim, core refusals map to the documented statuses,
 * and the JSON contract of both documents as the console will read it.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, ConsoleStatementFlowIntegrationTest.StubConfig.class})
class ConsoleStatementFlowIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        SettableCorePort settableCorePort() {
            return new SettableCorePort();
        }

        @Bean
        @Primary
        SettableOperatorPort settableOperatorPort() {
            return new SettableOperatorPort();
        }
    }

    private static final String OPERATOR_BASIC = "Basic b3BlcmF0b3I6dGVsbGVyLXB3";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);
    private static final String WALLET_EXTERNAL_ID = "3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet";

    private static final OperatorLoanView LOAN = new OperatorLoanView(
            "9", "000000009", "biz-loan-77", "KES", "SME Working Capital", "Active", true,
            new MinorUnits(500_000, "KES"), new BigDecimal("24.00"), "12 monthly repayments",
            LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1), "Shumba Traders", "0771234567",
            new OperatorLoanView.LoanPosition(375_000, 38_000, 0, 1_500, 414_500, 48_667,
                    LocalDate.of(2026, 8, 1)));

    @Autowired
    WebApplicationContext context;

    @Autowired
    SettableCorePort stubPort;

    @Autowired
    SettableOperatorPort stubOperatorPort;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        stubOperatorPort.onAuthorize = (credential, accountId) -> {
            assertThat(credential.authorizationHeader()).isEqualTo(OPERATOR_BASIC);
            assertThat(accountId).isEqualTo("17");
            return new OperatorAccountView(WALLET_EXTERNAL_ID, DepositAccountKind.SAVINGS, "KES",
                    "000000017", "Shumba Traders", "0771234567");
        };
        stubPort.onListTransactions = query -> {
            if (query.from() == null) {
                return new TransactionPage(List.of(
                        new TransactionEntry("9", null, TransactionDirection.CREDIT, "Deposit",
                                new MinorUnits(2_000L, "KES"), new MinorUnits(10_000L, "KES"),
                                FROM.minusDays(3), false)),
                        null);
            }
            return new TransactionPage(List.of(
                    new TransactionEntry("12", null, TransactionDirection.CREDIT, "Deposit",
                            new MinorUnits(5_000L, "KES"), new MinorUnits(15_000L, "KES"),
                            FROM.plusDays(2), false)),
                    1L);
        };
        stubOperatorPort.onAuthorizeLoan = (credential, loanId) -> {
            assertThat(credential.authorizationHeader()).isEqualTo(OPERATOR_BASIC);
            assertThat(loanId).isEqualTo("9");
            return LOAN;
        };
        // One page, newest first: a repayment in August, the disbursement in March.
        stubOperatorPort.onListLoanTransactions = (credential, loanId, page, pageSize) -> {
            assertThat(credential.authorizationHeader()).isEqualTo(OPERATOR_BASIC);
            return new LoanTransactionPage(List.of(
                    new LoanTransactionEntry("902", "rcpt-4411", FROM.plusDays(0), "Repayment",
                            LoanEntryKind.REPAYMENT, new MinorUnits(47_500, "KES"),
                            new MinorUnits(41_667, "KES"), new MinorUnits(5_500, "KES"),
                            new MinorUnits(333, "KES"), new MinorUnits(0, "KES"),
                            -41_667, 458_333L, false),
                    new LoanTransactionEntry("901", null, LocalDate.of(2026, 3, 1), "Disbursement",
                            LoanEntryKind.DISBURSEMENT, new MinorUnits(500_000, "KES"),
                            null, null, null, null, 500_000, 500_000L, false)),
                    2L);
        };
    }

    // ---------------------------------------------------------------- deposit accounts

    @Test
    void operatorGetsTheBankStatementAsJson() throws Exception {
        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("000000017"))
                .andExpect(jsonPath("$.accountType").value("SAVINGS"))
                .andExpect(jsonPath("$.customerName").value("Shumba Traders"))
                .andExpect(jsonPath("$.openingBalanceMinor").value(10_000))
                .andExpect(jsonPath("$.closingBalanceMinor").value(15_000))
                .andExpect(jsonPath("$.lines[0].id").value("12"));
    }

    /**
     * A fixed deposit is served by the SAME endpoint — in the core it is the
     * same account family behind the same reads — and the response says what
     * it is, so the console can offer the button on the deposit screens and
     * title what it shows. Pinned on the wire so the kind cannot go missing
     * from the JSON the way balanceNeutral once did.
     */
    @Test
    void fixedDepositStatementsThroughTheSameEndpointAndSaysSo() throws Exception {
        stubOperatorPort.onAuthorize = (credential, accountId) ->
                new OperatorAccountView(null, DepositAccountKind.FIXED_DEPOSIT, "KES",
                        "000000003", "Chipo Ndlovu", null);

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("000000003"))
                .andExpect(jsonPath("$.accountType").value("FIXED_DEPOSIT"))
                .andExpect(jsonPath("$.closingBalanceMinor").value(15_000));

        MvcResult csv = mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "csv")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .startsWith("InnBucks Fixed Deposit Statement\n");
    }

    /**
     * The JSON contract for no-money entries — asserted on the WIRE, because
     * the flag once made it into the CSV renderer and the internal line model
     * but not the JSON view DTO, and nothing here noticed. Same shape the
     * console dev reproduced: a Waive Charge in range.
     */
    @Test
    void balanceNeutralLinesAreFlaggedInTheJsonAndExcludedFromTotals() throws Exception {
        stubPort.onListTransactions = query -> {
            if (query.from() == null) {
                return new TransactionPage(List.of(
                        new TransactionEntry("9", null, TransactionDirection.CREDIT, "Deposit",
                                new MinorUnits(2_000L, "KES"), new MinorUnits(10_000L, "KES"),
                                FROM.minusDays(3), false)),
                        null);
            }
            return new TransactionPage(List.of(
                    new TransactionEntry("14", null, TransactionDirection.DEBIT, "Withdrawal",
                            new MinorUnits(1_000L, "KES"), new MinorUnits(14_000L, "KES"),
                            FROM.plusDays(9), false),
                    new TransactionEntry("13", null, TransactionDirection.DEBIT, "Waive Charge",
                            new MinorUnits(1_000L, "KES"), new MinorUnits(15_000L, "KES"),
                            FROM.plusDays(5), false, true),
                    new TransactionEntry("12", null, TransactionDirection.CREDIT, "Deposit",
                            new MinorUnits(5_000L, "KES"), new MinorUnits(15_000L, "KES"),
                            FROM.plusDays(2), false)),
                    3L);
        };

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCreditsMinor").value(5_000))
                .andExpect(jsonPath("$.totalDebitsMinor").value(1_000))
                .andExpect(jsonPath("$.closingBalanceMinor").value(14_000))
                .andExpect(jsonPath("$.lines[0].balanceNeutral").value(false))
                .andExpect(jsonPath("$.lines[1].id").value("13"))
                .andExpect(jsonPath("$.lines[1].balanceNeutral").value(true))
                .andExpect(jsonPath("$.lines[1].amountMinor").value(1_000))
                .andExpect(jsonPath("$.lines[1].balanceAfterMinor").value(15_000))
                .andExpect(jsonPath("$.lines[2].balanceNeutral").value(false));
    }

    @Test
    void pdfDownloadsWithTheAccountNumberInTheFilename() throws Exception {
        MvcResult result = mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "pdf")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("innbucks-statement-0017")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void missingCredentialIs401FromOurControllerNotTheResourceServer() throws Exception {
        AtomicInteger portCalls = new AtomicInteger();
        stubOperatorPort.onAuthorize = (credential, accountId) -> {
            portCalls.incrementAndGet();
            throw new IllegalStateException("must not be called");
        };

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized())
                // The distinguishing mark: the resource server's 401 has no
                // problem body; ours names the errorCode. This is the proof
                // the permitAll actually took.
                .andExpect(jsonPath("$.errorCode").value("operator_credentials_required"));

        assertThat(portCalls.get()).isZero();
    }

    @Test
    void coreRejectedCredentialIs401() throws Exception {
        stubOperatorPort.onAuthorize = (credential, accountId) -> {
            throw new CoreAuthException(CoreProvider.FINERACT, "bad operator credential", null);
        };

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("operator_unauthorized"));
    }

    @Test
    void notPermittedAndMissingAccountsAnswerIdentically() throws Exception {
        stubOperatorPort.onAuthorize = (credential, accountId) -> {
            throw new CoreClientException(CoreProvider.FINERACT, "not found or not permitted", null);
        };

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("account_not_accessible"));
    }

    /**
     * The majority console case: a branch-created account with no external
     * reference. The transaction reads must be addressed by the CORE account
     * id the operator quoted — the stub refuses anything else, so a
     * regression back to external-ref addressing fails loudly here.
     */
    @Test
    void branchAccountWithoutExternalReferenceStillGetsItsStatement() throws Exception {
        stubOperatorPort.onAuthorize = (credential, accountId) ->
                new OperatorAccountView(null, DepositAccountKind.SAVINGS, "KES", "000000023",
                        "Walk In", null);
        stubPort.onListTransactions = query -> {
            assertThat(query.coreAccountId()).isEqualTo("17");
            assertThat(query.account()).isNull();
            if (query.from() == null) {
                return new TransactionPage(List.of(), 0L);
            }
            return new TransactionPage(List.of(
                    new TransactionEntry("41", null, TransactionDirection.CREDIT, "Deposit",
                            new MinorUnits(5_000L, "KES"), new MinorUnits(5_000L, "KES"),
                            FROM.plusDays(2), false)),
                    1L);
        };

        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("000000023"))
                .andExpect(jsonPath("$.customerName").value("Walk In"))
                .andExpect(jsonPath("$.openingBalanceMinor").value(0))
                .andExpect(jsonPath("$.closingBalanceMinor").value(5_000));
    }

    // ---------------------------------------------------------------- loans

    /** The loan JSON as the console will read it — every header, total and line field on the wire. */
    @Test
    void operatorGetsTheLoanStatementAsJsonForThePeriod() throws Exception {
        mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value("9"))
                .andExpect(jsonPath("$.accountNumber").value("000000009"))
                .andExpect(jsonPath("$.externalId").value("biz-loan-77"))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.borrowerName").value("Shumba Traders"))
                .andExpect(jsonPath("$.borrowerMobile").value("0771234567"))
                .andExpect(jsonPath("$.productName").value("SME Working Capital"))
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.principalMinor").value(500_000))
                .andExpect(jsonPath("$.annualInterestRate").value(24.00))
                .andExpect(jsonPath("$.term").value("12 monthly repayments"))
                .andExpect(jsonPath("$.disbursedOn").value("2026-03-01"))
                .andExpect(jsonPath("$.maturityDate").value("2027-03-01"))
                .andExpect(jsonPath("$.from").value("2026-08-01"))
                .andExpect(jsonPath("$.to").value("2026-08-31"))
                // The March disbursement is before the period: it anchors the
                // opening principal and is NOT a line.
                .andExpect(jsonPath("$.openingPrincipalMinor").value(500_000))
                .andExpect(jsonPath("$.closingPrincipalMinor").value(458_333))
                .andExpect(jsonPath("$.totals.disbursedMinor").value(0))
                .andExpect(jsonPath("$.totals.repaidMinor").value(47_500))
                .andExpect(jsonPath("$.totals.principalRepaidMinor").value(41_667))
                .andExpect(jsonPath("$.totals.interestRepaidMinor").value(5_500))
                .andExpect(jsonPath("$.totals.feesRepaidMinor").value(333))
                .andExpect(jsonPath("$.totals.penaltiesRepaidMinor").value(0))
                .andExpect(jsonPath("$.position.principalOutstandingMinor").value(375_000))
                .andExpect(jsonPath("$.position.totalOverdueMinor").value(48_667))
                .andExpect(jsonPath("$.position.overdueSince").value("2026-08-01"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].id").value("902"))
                .andExpect(jsonPath("$.lines[0].reference").value("rcpt-4411"))
                .andExpect(jsonPath("$.lines[0].kind").value("REPAYMENT"))
                .andExpect(jsonPath("$.lines[0].amountMinor").value(47_500))
                .andExpect(jsonPath("$.lines[0].principalMinor").value(41_667))
                .andExpect(jsonPath("$.lines[0].interestMinor").value(5_500))
                .andExpect(jsonPath("$.lines[0].feesMinor").value(333))
                .andExpect(jsonPath("$.lines[0].penaltiesMinor").value(0))
                .andExpect(jsonPath("$.lines[0].principalDeltaMinor").value(-41_667))
                .andExpect(jsonPath("$.lines[0].principalBalanceAfterMinor").value(458_333))
                .andExpect(jsonPath("$.lines[0].reversed").value(false));
    }

    /** No period at all is the usual request: the life of the loan, to today. */
    @Test
    void loanStatementWithoutAPeriodCoversTheLifeOfTheLoan() throws Exception {
        mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.to").exists())
                .andExpect(jsonPath("$.openingPrincipalMinor").value(0))
                .andExpect(jsonPath("$.totals.disbursedMinor").value(500_000))
                .andExpect(jsonPath("$.closingPrincipalMinor").value(458_333))
                .andExpect(jsonPath("$.lines[0].id").value("901"))
                .andExpect(jsonPath("$.lines[0].kind").value("DISBURSEMENT"))
                .andExpect(jsonPath("$.lines[0].principalMinor").doesNotExist())
                .andExpect(jsonPath("$.lines[1].id").value("902"));
    }

    @Test
    void loanPdfAndCsvDownloadWithTheLoanNumberInTheFilename() throws Exception {
        MvcResult pdf = mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .param("format", "pdf")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("innbucks-loan-statement-0009-inception-")))
                .andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        MvcResult csv = mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "csv")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("innbucks-loan-statement-0009-2026-08-01-2026-08-31.csv")))
                .andReturn();
        String body = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("InnBucks Loan Statement\n");
        assertThat(body).contains("Opening principal,\"5,000.00\"\n");
        assertThat(body).contains("Closing principal,\"4,583.33\"\n");
    }

    @Test
    void loanRefusalsMapLikeTheDepositOnes() throws Exception {
        mockMvc.perform(get("/console/loans/{id}/statement", "9"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("operator_credentials_required"));

        stubOperatorPort.onAuthorizeLoan = (credential, loanId) -> {
            throw new CoreAuthException(CoreProvider.FINERACT, "bad operator credential", null);
        };
        mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("operator_unauthorized"));

        stubOperatorPort.onAuthorizeLoan = (credential, loanId) -> {
            throw new CoreClientException(CoreProvider.FINERACT, "not found or not permitted", null);
        };
        mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("account_not_accessible"));

        mockMvc.perform(get("/console/loans/{id}/statement", "9")
                        .param("from", "2026-08-31").param("to", "2026-08-01")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("statement_period_invalid"));
    }
}
