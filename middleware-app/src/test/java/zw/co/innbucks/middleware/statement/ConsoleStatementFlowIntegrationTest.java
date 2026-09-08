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
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;
import zw.co.innbucks.middleware.support.SettableOperatorPort;

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
 * The console (bank-issued) statement over real HTTP wiring. What is pinned
 * here and nowhere else: Spring Security actually PERMITS the path so the
 * controller's own Fineract-delegated auth runs (a 401 produced by OUR
 * problem body, not by the resource server), the operator's credential
 * reaches the port verbatim, and core refusals map to the documented
 * statuses.
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
            return new OperatorAccountView(WALLET_EXTERNAL_ID, "KES", "000000017",
                    "Shumba Traders", "0771234567");
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
    }

    @Test
    void operatorGetsTheBankStatementAsJson() throws Exception {
        mockMvc.perform(get("/console/savings-accounts/{id}/statement", "17")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, OPERATOR_BASIC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("000000017"))
                .andExpect(jsonPath("$.customerName").value("Shumba Traders"))
                .andExpect(jsonPath("$.openingBalanceMinor").value(10_000))
                .andExpect(jsonPath("$.closingBalanceMinor").value(15_000))
                .andExpect(jsonPath("$.lines[0].id").value("12"));
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
                new OperatorAccountView(null, "KES", "000000023", "Walk In", null);
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
}
