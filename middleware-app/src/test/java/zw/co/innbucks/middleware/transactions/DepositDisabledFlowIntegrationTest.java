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
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cell with customer deposits switched OFF — the production posture. The
 * shared test configuration turns them on (the flow ITs fund wallets that
 * way), so this class flips the switch back and gets its own context.
 *
 * <p>What it proves end to end: through the real security chain, exception
 * advice and Postgres, a deposit is refused 403 {@code deposit_disabled} and
 * leaves NOTHING — no idempotency record, no ledger row, no audit row, and the
 * core is never asked, not even for the ownership listing. And the switch is
 * about deposits only: a withdrawal on the same cell still runs.
 *
 * <p>Transaction alerts are off here: the withdrawal's alert would otherwise
 * read the core on a background thread, and could land after the next test
 * has reset {@code coreCalls}. TransactionFlowIntegrationTest owns alerts.
 */
@SpringBootTest(properties = {
        "innbucks.transactions.deposit.enabled=false",
        "innbucks.notify.transactions.enabled=false"
})
@Import({PostgresTestContainer.class, DepositDisabledFlowIntegrationTest.StubConfig.class})
class DepositDisabledFlowIntegrationTest {

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
    String wallet;
    String bearer;
    final AtomicInteger coreCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE idempotency_record, ledger_transaction_event, ledger_transaction, "
                + "audit_event, refresh_token, customer CASCADE");
        UUID customerId = UUID.randomUUID();
        wallet = customerId + ":wallet";
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider,
                                      core_external_id, status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', '+254712000077', 'x', 'basic', 'FINERACT', ?, 'active', 0, ?, ?)
                """, customerId, customerId.toString(), Timestamp.from(now), Timestamp.from(now));
        bearer = "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null, null));

        coreCalls.set(0);
        stubPort.onListAccounts = ref -> {
            coreCalls.incrementAndGet();
            return List.of(new DepositAccountSummary(
                    new AccountRef(wallet), "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES")));
        };
        stubPort.onDeposit = (cmd, key) -> {
            coreCalls.incrementAndGet();
            return new TransactionResult(new TxRef("CORE-901"), TransactionState.COMPLETED);
        };
        stubPort.onWithdraw = (cmd, key) ->
                new TransactionResult(new TxRef("CORE-902"), TransactionState.COMPLETED);
    }

    @Test
    void aDepositIsRefusedBeforeAnythingIsAskedOrWritten() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "dep-off-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amountMinor":250000,"currency":"KES","narrative":"cash in"}
                                """.formatted(wallet)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("deposit_disabled"))
                .andExpect(jsonPath("$.title").value("Deposits unavailable"))
                .andExpect(jsonPath("$.detail").value("Deposits are not available in this deployment."));

        assertThat(coreCalls.get()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class))
                .isZero();
    }

    @Test
    void anAccountTheCallerDoesNotOwnGetsTheSameRefusal() throws Exception {
        // The switch runs ahead of ownership, so a switched-off cell never
        // spends a core round trip on any deposit — and answers every caller
        // the same way, whatever account they name.
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "dep-off-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"somebody-else:wallet","amountMinor":1000,"currency":"KES"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("deposit_disabled"));

        assertThat(coreCalls.get()).isZero();
    }

    @Test
    void withdrawalsAreUntouchedByTheDepositSwitch() throws Exception {
        // Below the basic step-up threshold (50000): this is about the switch's
        // scope, not step-up.
        mockMvc.perform(post("/transactions/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "wd-on-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amountMinor":9999,"currency":"KES"}
                                """.formatted(wallet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT type FROM ledger_transaction", String.class)).isEqualTo("WITHDRAWAL");
    }
}
