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
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    }

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
        stubPort.onListAccounts = ref -> List.of(new DepositAccountSummary(
                new AccountRef(wallet), "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES")));
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
