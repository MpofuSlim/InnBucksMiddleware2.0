package zw.co.innbucks.middleware.transactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.CustomerScopes;
import zw.co.innbucks.middleware.auth.jwt.JwtIssuer;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.otp.SmsSender;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import java.time.LocalDate;

/**
 * The full money-movement guard stack over HTTP: JWT → ownership → namespaced
 * idempotency → ledgered execution, plus /me/profile and /me/accounts reads.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, TransactionFlowIntegrationTest.StubConfig.class})
class TransactionFlowIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        SettableCorePort settableCorePort() {
            return new SettableCorePort();
        }

        /** Captures what the transaction-alert listener actually dispatched. */
        @Bean
        @Primary
        CapturingSmsSender capturingSmsSender() {
            return new CapturingSmsSender();
        }
    }

    static class CapturingSmsSender implements SmsSender {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public void send(String e164Msisdn, String body) {
            messages.add(e164Msisdn + " | " + body);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SettableCorePort stubPort;

    @Autowired
    JwtIssuer issuer;

    @Autowired
    CapturingSmsSender smsSender;

    MockMvc mockMvc;
    UUID customerId;
    String wallet;
    String bearer;
    final AtomicInteger coreDeposits = new AtomicInteger();

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

        coreDeposits.set(0);
        smsSender.messages.clear();
        stubPort.onListAccounts = ref -> List.of(new DepositAccountSummary(
                new AccountRef(wallet), "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES")));
        stubPort.onGetBalance = account -> new AccountBalance(account,
                new MinorUnits(150000L, "KES"), new MinorUnits(150000L, "KES"));
        stubPort.onGetProfile = ref -> new CustomerProfile(
                new zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef(ref.externalId()),
                "Tariro", "Moyo", "ACTIVE");
        stubPort.onDeposit = (cmd, key) -> {
            coreDeposits.incrementAndGet();
            return new TransactionResult(new TxRef("CORE-501"), TransactionState.COMPLETED);
        };
    }

    private String depositBody(long amount) {
        return """
                {"accountId":"%s","amountMinor":%d,"currency":"KES","narrative":"cash in"}
                """.formatted(wallet, amount);
    }

    @Test
    void profileAndAccountsReadThroughThePort() throws Exception {
        mockMvc.perform(get("/me/profile").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.firstName").value("Tariro"))
                .andExpect(jsonPath("$.msisdn").value("+254712000099"));

        mockMvc.perform(get("/me/accounts").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(wallet))
                .andExpect(jsonPath("$[0].balanceMinor").value(150000));
    }

    @Test
    void statementReadsThroughThePortAndEchoesPaging() throws Exception {
        stubPort.onListTransactions = query -> {
            assertThat(query.account().externalId()).isEqualTo(wallet);
            assertThat(query.offset()).isEqualTo(20);
            assertThat(query.limit()).isEqualTo(5);
            assertThat(query.from()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(query.to()).isEqualTo(LocalDate.of(2026, 7, 31));
            return new TransactionPage(List.of(
                    new TransactionEntry("15", "ref-abc", TransactionDirection.DEBIT, "Withdrawal",
                            new MinorUnits(60000L, "KES"), new MinorUnits(1500L, "KES"),
                            LocalDate.of(2026, 7, 31), false),
                    // No externalRef: booked on the core, never through us.
                    new TransactionEntry("9", null, TransactionDirection.CREDIT, "Interest Posting",
                            new MinorUnits(250L, "KES"), null,
                            LocalDate.of(2026, 7, 1), false)),
                    143L);
        };

        mockMvc.perform(get("/me/accounts/{id}/transactions", wallet)
                        .param("from", "2026-07-01").param("to", "2026-07-31")
                        .param("offset", "20").param("limit", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(wallet))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.totalCount").value(143))
                .andExpect(jsonPath("$.offset").value(20))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.entries[0].id").value("15"))
                .andExpect(jsonPath("$.entries[0].reference").value("ref-abc"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amountMinor").value(60000))
                .andExpect(jsonPath("$.entries[0].runningBalanceMinor").value(1500))
                .andExpect(jsonPath("$.entries[0].date").value("2026-07-31"))
                // Core-booked entries carry no reference and may have no running
                // balance — neither may be invented.
                .andExpect(jsonPath("$.entries[1].reference").doesNotExist())
                .andExpect(jsonPath("$.entries[1].runningBalanceMinor").doesNotExist());
    }

    @Test
    void statementRefusesAnAccountTheCallerDoesNotOwn() throws Exception {
        // Ownership is checked against the CORE's account list before the
        // statement is fetched — the port must never be asked at all.
        AtomicInteger statementCalls = new AtomicInteger();
        stubPort.onListTransactions = query -> {
            statementCalls.incrementAndGet();
            return new TransactionPage(List.of(), 0L);
        };

        mockMvc.perform(get("/me/accounts/{id}/transactions", "someone-else:wallet")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());

        assertThat(statementCalls.get()).isZero();
    }

    @Test
    void statementRejectsAnOversizedPageRatherThanPullingTheWholeHistory() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/transactions", wallet)
                        .param("limit", "5000")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statementRequiresAToken() throws Exception {
        mockMvc.perform(get("/me/accounts/{id}/transactions", wallet))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/me/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/transactions/deposit")
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody(1000)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void depositSucceedsAndIsLedgered() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "dep-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody(250000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.coreTxRef").value("CORE-501"));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT type, status, amount_minor, customer_id::text AS cid FROM ledger_transaction"))
                .containsEntry("type", "DEPOSIT")
                .containsEntry("status", "COMPLETED")
                .containsEntry("amount_minor", 250000L)
                .containsEntry("cid", customerId.toString());
    }

    @Test
    void retryWithTheSameKeyNeverRunsTheMovementTwice() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "dep-2")
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody(1000)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "dep-2")
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody(1000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertThat(coreDeposits.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void coreRejectionIs422AndLedgeredAsFailed() throws Exception {
        stubPort.onWithdraw = (cmd, key) -> {
            throw new CoreClientException(CoreProvider.FINERACT, "Insufficient account balance.", null);
        };

        // Amount stays BELOW the basic step-up threshold (50000) — this test is
        // about the core rejection path; StepUpFlowIntegrationTest owns step-up.

        mockMvc.perform(post("/transactions/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "wd-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amountMinor":9999,"currency":"KES"}
                                """.formatted(wallet)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("core_rejected"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transaction", String.class)).isEqualTo("FAILED");
    }

    @Test
    void unknownOutcomeRendersAsProcessingWithAParkedRow() throws Exception {
        stubPort.onTransfer = (cmd, key) -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT,
                    cmd.externalRef(), "read timeout mid-flight", null);
        };

        mockMvc.perform(post("/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "tr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"%s","toAccountId":"other:wallet",
                                 "amountMinor":5000,"currency":"KES","narrative":"rent"}
                                """.formatted(wallet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        // The row is parked for the reconciler — the customer is told PROCESSING,
        // never FAILED, because the money may have moved.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transaction", String.class)).isEqualTo("UNKNOWN");

        // And NOT a word to the customer. An UNKNOWN row is precisely the case
        // where any message we could send — "sent" or "failed" — might be false.
        assertThat(smsSender.messages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    /**
     * The wiring proof for transaction alerts. The listener is an
     * {@code AFTER_COMMIT} hook dispatched onto a background pool, so no unit
     * test can show it actually fires inside a real context — only this can.
     */
    @Test
    void aCompletedDepositAlertsTheCustomer() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "dep-sms-1")
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody(25000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        String alert = smsSender.messages.poll(10, TimeUnit.SECONDS);
        assertThat(alert).isNotNull();
        assertThat(alert)
                .startsWith("+254712000099 | InnBucks. Account ending ")
                .contains(" credited with KES 250.00 on ")
                // The core's own reference, not our 64-char SHA-256 external ref.
                .contains("Ref. CORE-501.")
                // Balance read for the account the message is about.
                .contains("Available balance KES 1,500.00.")
                .endsWith("Narration - cash in.");
    }

    @Test
    void foreignAccountIsForbiddenBeforeAnythingIsWritten() throws Exception {
        mockMvc.perform(post("/transactions/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "wd-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"somebody-else:wallet","amountMinor":1000,"currency":"KES"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("account_ownership_mismatch"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class))
                .isZero();
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(depositBody(1000)))
                .andExpect(status().isBadRequest());
    }
}
