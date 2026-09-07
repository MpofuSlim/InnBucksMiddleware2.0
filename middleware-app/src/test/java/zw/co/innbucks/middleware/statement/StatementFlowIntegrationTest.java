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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The statement DOCUMENT endpoint over real HTTP wiring: JWT → ownership →
 * the opening-balance probe → assembly → the three renderings. The stub port
 * plays the core; what is pinned here is that the web layer, security and
 * service actually connect — the balance-policy edge cases live in the
 * middleware-core unit tests.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, StatementFlowIntegrationTest.StubConfig.class})
class StatementFlowIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        SettableCorePort settableCorePort() {
            return new SettableCorePort();
        }
    }

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SettableCorePort stubPort;

    @Autowired
    JwtIssuer issuer;

    MockMvc mockMvc;
    UUID customerId;
    String wallet;
    String bearer;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE idempotency_record, ledger_transaction_event, ledger_transaction, "
                + "audit_event, refresh_token, customer CASCADE");
        customerId = UUID.randomUUID();
        wallet = customerId + ":wallet";
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider,
                                      core_external_id, status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', '+254712000099', 'x', 'basic', 'FINERACT', ?, 'active', 0, ?, ?)
                """, customerId, customerId.toString(), Timestamp.from(now), Timestamp.from(now));
        bearer = "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null, null));

        stubPort.onListAccounts = ref -> List.of(new DepositAccountSummary(
                new AccountRef(wallet), "InnBucks Wallet", "KES", new MinorUnits(150_000L, "KES")));
        stubPort.onGetProfile = ref -> new CustomerProfile(
                new CoreCustomerRef(ref.externalId()), "Tariro", "Moyo", "ACTIVE");
        stubPort.onListTransactions = this::respondToStatementQueries;
    }

    /**
     * Plays the core for one August: an anchor probe (open-ended from, ends
     * the day before the period, limit 5) answered with a balance-bearing
     * entry, then one short period page NEWEST first — asserting the query
     * shapes so a regression in either query fails loudly here.
     */
    private TransactionPage respondToStatementQueries(
            zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery query) {
        assertThat(query.account().externalId()).isEqualTo(wallet);
        if (query.from() == null) {
            assertThat(query.to()).isEqualTo(FROM.minusDays(1));
            assertThat(query.limit()).isEqualTo(5);
            return new TransactionPage(List.of(
                    new TransactionEntry("9", null, TransactionDirection.CREDIT, "Deposit",
                            new MinorUnits(2_000L, "KES"), new MinorUnits(10_000L, "KES"),
                            FROM.minusDays(3), false)),
                    null);
        }
        assertThat(query.from()).isEqualTo(FROM);
        assertThat(query.to()).isEqualTo(TO);
        return new TransactionPage(List.of(
                new TransactionEntry("15", "ref-abc", TransactionDirection.DEBIT, "Withdrawal",
                        new MinorUnits(1_000L, "KES"), new MinorUnits(14_000L, "KES"),
                        FROM.plusDays(9), false),
                new TransactionEntry("12", null, TransactionDirection.CREDIT, "Deposit",
                        new MinorUnits(5_000L, "KES"), new MinorUnits(15_000L, "KES"),
                        FROM.plusDays(2), false)),
                2L);
    }

    @Test
    void jsonStatementAnchorsBalancesAndReadsChronologically() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(wallet))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.customerName").value("Tariro Moyo"))
                .andExpect(jsonPath("$.msisdn").value("+254712000099"))
                .andExpect(jsonPath("$.openingBalanceMinor").value(10_000))
                .andExpect(jsonPath("$.closingBalanceMinor").value(14_000))
                .andExpect(jsonPath("$.totalCreditsMinor").value(5_000))
                .andExpect(jsonPath("$.totalDebitsMinor").value(1_000))
                // Oldest first — the wire served newest first.
                .andExpect(jsonPath("$.lines[0].id").value("12"))
                .andExpect(jsonPath("$.lines[0].balanceAfterMinor").value(15_000))
                .andExpect(jsonPath("$.lines[1].id").value("15"))
                .andExpect(jsonPath("$.lines[1].reference").value("ref-abc"));
    }

    @Test
    void pdfFormatDownloadsARealPdf() throws Exception {
        MvcResult result = mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("innbucks-statement-")))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void csvFormatDownloadsTheSameNumbers() throws Exception {
        MvcResult result = mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn();

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Opening balance,100.00");
        assertThat(csv).contains("Closing balance,140.00");
    }

    @Test
    void refusesAnAccountTheCallerDoesNotOwnBeforeAskingTheCore() throws Exception {
        AtomicInteger statementReads = new AtomicInteger();
        stubPort.onListTransactions = query -> {
            statementReads.incrementAndGet();
            return new TransactionPage(List.of(), 0L);
        };

        mockMvc.perform(get("/me/accounts/{id}/statement", "someone-else:wallet")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());

        assertThat(statementReads.get()).isZero();
    }

    @Test
    void anonymousCallersAreRefused() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-01").param("to", "2026-08-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invertedPeriodIs400WithAStableErrorCode() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-31").param("to", "2026-08-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("statement_period_invalid"));
    }

    @Test
    void unknownFormatIs400NotASilentJsonFallback() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/statement", wallet)
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("format", "docx")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("statement_format_invalid"));
    }
}
