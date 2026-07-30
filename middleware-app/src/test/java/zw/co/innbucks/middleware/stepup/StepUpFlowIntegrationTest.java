package zw.co.innbucks.middleware.stepup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The step-up guard end to end over HTTP: a transfer at/above the caller's
 * tier threshold 403s with a fingerprint, the authenticated step-up endpoints
 * approve exactly that transaction, the approval token is single-use, and
 * small movements pass untouched. Uses the dev-style fixed OTP code (allowed
 * under the test profile) so the SMS never needs reading.
 */
@SpringBootTest(properties = "innbucks.otp.fixed-code=000000")
@Import({PostgresTestContainer.class, StepUpFlowIntegrationTest.StubConfig.class})
class StepUpFlowIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        SettableCorePort settableCorePort() {
            return new SettableCorePort();
        }
    }

    private static final String MSISDN = "+254712000099";
    /** innbucks.stepup.thresholds.basic in the test yaml. */
    private static final long BASIC_THRESHOLD = 50000L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    SettableCorePort stubPort;

    @Autowired
    JwtIssuer issuer;

    final ObjectMapper objectMapper = new ObjectMapper();
    final AtomicInteger coreTransfers = new AtomicInteger();

    MockMvc mockMvc;
    UUID customerId;
    String wallet;
    String bearer;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE idempotency_record, ledger_transaction_event, ledger_transaction, "
                + "audit_event, refresh_token, otp_challenge, consumed_verification_token, customer CASCADE");
        customerId = UUID.randomUUID();
        wallet = customerId + ":wallet";
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider,
                                      core_external_id, status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', ?, 'x', 'basic', 'FINERACT', ?, 'active', 0, ?, ?)
                """, customerId, MSISDN, customerId.toString(), Timestamp.from(now), Timestamp.from(now));
        bearer = "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null, null));

        coreTransfers.set(0);
        stubPort.onListAccounts = ref -> List.of(new DepositAccountSummary(
                new AccountRef(wallet), "InnBucks Wallet", "KES", new MinorUnits(500000L, "KES")));
        stubPort.onTransfer = (cmd, key) -> {
            coreTransfers.incrementAndGet();
            return new TransactionResult(new TxRef("CORE-701"), TransactionState.COMPLETED);
        };
        stubPort.onWithdraw = (cmd, key) ->
                new TransactionResult(new TxRef("CORE-702"), TransactionState.COMPLETED);
    }

    private String transferBody(long amount) {
        return """
                {"fromAccountId":"%s","toAccountId":"other:wallet","amountMinor":%d,
                 "currency":"KES","narrative":"rent"}
                """.formatted(wallet, amount);
    }

    private MvcResult transfer(long amount, String idempotencyKey, String stepUpToken) throws Exception {
        var request = post("/transactions/transfer")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(amount));
        if (stepUpToken != null) {
            request = request.header("X-Step-Up-Token", stepUpToken);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String approve(String txnFp) throws Exception {
        mockMvc.perform(post("/auth/step-up/request").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());
        MvcResult verify = mockMvc.perform(post("/auth/step-up/verify")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"000000","txnFp":"%s"}
                                """.formatted(txnFp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(verify.getResponse().getContentAsString())
                .get("verificationToken").asText();
    }

    @Test
    void highValueTransferRequiresApprovalThenSucceeds() throws Exception {
        // 1. Above the basic threshold without a token: refused, no core call,
        //    no idempotency/ledger state, fingerprint echoed.
        MvcResult refused = transfer(BASIC_THRESHOLD + 10000, "key-1", null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        JsonNode problem = objectMapper.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.get("errorCode").asText()).isEqualTo("step_up_required");
        String txnFp = problem.get("txnFp").asText();
        assertThat(txnFp).matches("^[0-9a-f]{64}$");
        assertThat(coreTransfers.get()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction", Integer.class)).isZero();

        // 2. Approve exactly that transaction, retry with the token: executes.
        String token = approve(txnFp);
        MvcResult approved = transfer(BASIC_THRESHOLD + 10000, "key-1", token);
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("SUCCESS");
        assertThat(coreTransfers.get()).isEqualTo(1);
    }

    @Test
    void approvalTokenIsSingleUse() throws Exception {
        String txnFp = objectMapper.readTree(
                        transfer(BASIC_THRESHOLD, "key-a", null).getResponse().getContentAsString())
                .get("txnFp").asText();
        String token = approve(txnFp);

        assertThat(transfer(BASIC_THRESHOLD, "key-a", token).getResponse().getStatus()).isEqualTo(200);

        // Same token, fresh idempotency key -> the movement would EXECUTE
        // again, so the consumed token must be rejected.
        MvcResult replay = transfer(BASIC_THRESHOLD, "key-b", token);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("errorCode").asText()).isEqualTo("verification_token_invalid");
        assertThat(coreTransfers.get()).isEqualTo(1);
    }

    @Test
    void approvalIsBoundToExactlyOneTransaction() throws Exception {
        String txnFp = objectMapper.readTree(
                        transfer(BASIC_THRESHOLD, "key-a", null).getResponse().getContentAsString())
                .get("txnFp").asText();
        String token = approve(txnFp);

        // Different amount => different fingerprint => the token must not approve it.
        MvcResult mismatch = transfer(BASIC_THRESHOLD + 1, "key-c", token);
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(mismatch.getResponse().getContentAsString())
                .get("errorCode").asText()).isEqualTo("verification_token_txn_mismatch");
        assertThat(coreTransfers.get()).isZero();
    }

    @Test
    void belowThresholdMovesWithoutStepUp() throws Exception {
        MvcResult small = transfer(BASIC_THRESHOLD - 1, "key-small", null);
        assertThat(small.getResponse().getStatus()).isEqualTo(200);
        assertThat(coreTransfers.get()).isEqualTo(1);
    }

    @Test
    void withdrawalsStepUpToo() throws Exception {
        mockMvc.perform(post("/transactions/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", "key-w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amountMinor":%d,"currency":"KES","narrative":"cash out"}
                                """.formatted(wallet, BASIC_THRESHOLD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("step_up_required"));
    }

    @Test
    void publicOtpEndpointsRefuseTheStepUpPurpose() throws Exception {
        mockMvc.perform(post("/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712000099","purpose":"STEP_UP"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("bad_request"));
        mockMvc.perform(post("/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712000099","purpose":"STEP_UP","code":"000000"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("bad_request"));
    }

    @Test
    void stepUpEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/auth/step-up/request"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/step-up/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"000000","txnFp":"%s"}
                                """.formatted("0".repeat(64))))
                .andExpect(status().isUnauthorized());
    }
}
