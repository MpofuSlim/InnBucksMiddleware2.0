package zw.co.innbucks.middleware.limits;

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
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.ledger.LedgerDraft;
import zw.co.innbucks.middleware.ledger.LedgerService;
import zw.co.innbucks.middleware.ledger.LedgerTransactionType;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;
import zw.co.innbucks.middleware.transactions.MovementRequests.TransferRequest;
import zw.co.innbucks.middleware.transactions.MovementService;
import zw.co.innbucks.middleware.transactions.TransactionResponse;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-tier payment limits against REAL Postgres: the advisory lock, the
 * READ COMMITTED sum and the PENDING-row-as-reservation all live in SQL, so
 * only a real database can prove them.
 *
 * <p>The packaged KE policy is dormant, so the values here arrive the way an
 * operator's emergency clamp would: as tighten-only overrides (anything
 * tightens an absent limit). Basic tier: 600.00 per payment, 800.00 a day,
 * 3,000.00 a month, 5 payments a day. The basic step-up threshold in the test
 * yaml is 500.00, below the per-payment cap, so approval can still fire.
 *
 * <p>SEPARATE CONTEXT on purpose: these overrides would change the outcome of
 * every other money-moving IT.
 */
@SpringBootTest(properties = {
        "innbucks.limits.override.basic.per-payment=60000",
        "innbucks.limits.override.basic.daily=80000",
        "innbucks.limits.override.basic.monthly=300000",
        "innbucks.limits.override.basic.daily-count=5"
})
@Import({PostgresTestContainer.class, PaymentLimitsIntegrationTest.StubConfig.class})
class PaymentLimitsIntegrationTest {

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

    @Autowired
    MovementService movementService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    PaymentLimitService limitService;

    @Autowired
    PaymentLimitsPolicyAuditor auditor;

    @Autowired
    PaymentLimitsPolicy policy;

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
        customerId = insertCustomer("+254712000301");
        wallet = customerId + ":wallet";
        bearer = "Bearer " + issuer.issue(new JwtIssuer.IssueRequest(
                customerId.toString(), Country.KE, "basic", CustomerScopes.DEFAULT, null));

        coreTransfers.set(0);
        stubPort.onListAccounts = ref -> List.of(new DepositAccountSummary(
                new AccountRef(ref.externalId() + ":wallet"), "InnBucks Wallet", "KES",
                new MinorUnits(10_000_000L, "KES"), AccountRestriction.NONE));
        stubPort.onTransfer = (cmd, key) -> {
            coreTransfers.incrementAndGet();
            return new TransactionResult(new TxRef("CORE-T"), TransactionState.COMPLETED);
        };
        stubPort.onWithdraw = (cmd, key) -> new TransactionResult(new TxRef("CORE-W"), TransactionState.COMPLETED);
        stubPort.onDeposit = (cmd, key) -> new TransactionResult(new TxRef("CORE-D"), TransactionState.COMPLETED);
    }

    private UUID insertCustomer(String msisdn) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider,
                                      core_external_id, status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', ?, 'x', 'basic', 'FINERACT', ?, 'active', 0, ?, ?)
                """, id, msisdn, id.toString(), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private MvcResult transfer(long amount, String key) throws Exception {
        return mockMvc.perform(post("/transactions/transfer")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"%s","toAccountId":"other:wallet","amountMinor":%d,
                                 "currency":"KES","narrative":"rent"}
                                """.formatted(wallet, amount)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode limits() throws Exception {
        return json(mockMvc.perform(get("/me/limits").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn());
    }

    private static JsonNode kind(JsonNode limits, String kind) {
        for (JsonNode node : limits.get("limits")) {
            if (kind.equals(node.get("kind").asText())) {
                return node;
            }
        }
        throw new AssertionError("no " + kind + " in " + limits);
    }

    private int liveLedgerRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transaction WHERE status <> 'FAILED'", Integer.class);
    }

    /**
     * Two payments that each fit, but not together, released at the same
     * instant: exactly one may pass. Driven through the service directly
     * (MockHttpServletRequest is not documented thread-safe), shaped like
     * LoginLockoutConcurrencyIntegrationTest.
     */
    @Test
    void twoConcurrentPaymentsThatTogetherExceedTheDailyLimitExactlyOneSucceeds() throws Exception {
        int threads = 2;
        CyclicBarrier together = new CyclicBarrier(threads);
        List<TransactionResponse> succeeded = new CopyOnWriteArrayList<>();
        List<Throwable> refused = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            String key = "conc-" + i;
            pool.submit(() -> {
                try {
                    together.await(10, TimeUnit.SECONDS);
                    IdempotencyService.Result<TransactionResponse> result = movementService.transfer(
                            customerId, key, new TransferRequest(wallet, "other:wallet", 45000L, "KES", "rent"),
                            null);
                    succeeded.add(result.body());
                } catch (PaymentLimitExceededException expected) {
                    refused.add(expected);
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("no hang").isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(succeeded).hasSize(1);
        assertThat(succeeded.get(0).status()).isEqualTo("SUCCESS");
        assertThat(refused).hasSize(1);
        assertThat(((PaymentLimitExceededException) refused.get(0)).refusal().kind()).isEqualTo(LimitKind.DAILY);
        assertThat(coreTransfers.get()).as("only one payment reached the core").isEqualTo(1);
        assertThat(liveLedgerRows()).isEqualTo(1);
    }

    /**
     * The binding layer on its own: no advisory read at all, both drafts
     * reserve at the same instant. Without the per-customer lock (or under
     * REPEATABLE READ) both would sum zero and both rows would land. Several
     * rounds, each on a fresh customer, to give a broken lock every chance
     * to show itself.
     */
    @Test
    void theBindingCheckSerialisesConcurrentReservations() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID customer = insertCustomer("+25471200040" + round);
            CyclicBarrier together = new CyclicBarrier(2);
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger refusedCount = new AtomicInteger();
            List<Throwable> unexpected = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            for (int i = 0; i < 2; i++) {
                LedgerDraft draft = new LedgerDraft(customer, LedgerTransactionType.TRANSFER,
                        customer + ":wallet", "other:wallet", new MinorUnits(45000L, "KES"), "rent",
                        "bind-" + round + "-" + i, CoreProvider.FINERACT);
                pool.submit(() -> {
                    try {
                        together.await(10, TimeUnit.SECONDS);
                        ledgerService.openPending(draft,
                                limitService.reservationGuard(KycTierResolution.of(KycTier.BASIC)));
                        opened.incrementAndGet();
                    } catch (PaymentLimitExceededException expected) {
                        refusedCount.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(unexpected).isEmpty();
            assertThat(opened.get()).as("round %d: exactly one reservation", round).isEqualTo(1);
            assertThat(refusedCount.get()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ledger_transaction WHERE customer_id = ?", Integer.class, customer))
                    .as("the refused reservation wrote no row").isEqualTo(1);
        }
    }

    /**
     * Count once: a replay of a payment that took the customer exactly to the
     * daily limit returns its recorded outcome — never limit_exceeded — and
     * adds nothing to what /me/limits reports.
     */
    @Test
    void aSameKeyReplayIsCountedOnce() throws Exception {
        MvcResult first = transfer(40000, "replay-a");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(transfer(40000, "replay-b").getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = transfer(40000, "replay-a");
        assertThat(replay.getResponse().getStatus()).as(replay.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(replay).get("transactionId").asText())
                .isEqualTo(json(first).get("transactionId").asText());
        assertThat(coreTransfers.get()).isEqualTo(2);
        assertThat(liveLedgerRows()).isEqualTo(2);

        JsonNode daily = kind(limits(), "daily");
        assertThat(daily.get("usedMinor").asLong()).isEqualTo(80000L);
        assertThat(daily.get("remainingMinor").asLong()).isZero();
        assertThat(kind(limits(), "daily_count").get("used").asLong()).isEqualTo(2L);

        // ...while a NEW payment, even of one cent, is refused.
        MvcResult fresh = transfer(1, "replay-c");
        assertThat(fresh.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(fresh).get("errorCode").asText()).isEqualTo("limit_exceeded");
        assertThat(json(fresh).get("limit").asText()).isEqualTo("daily");
        assertThat(json(fresh).get("remainingMinor").asLong()).isZero();
    }

    /** The screen and the refusal read the same rules, query and windows. */
    @Test
    void meLimitsAgreesWithTheRefusal() throws Exception {
        assertThat(transfer(30000, "agree-1").getResponse().getStatus()).isEqualTo(200);

        JsonNode view = limits();
        assertThat(view.get("kycTier").asText()).isEqualTo("basic");
        assertThat(view.get("currency").asText()).isEqualTo("KES");
        assertThat(view.get("stepUpAtOrAboveMinor").asLong()).isEqualTo(50000L);
        // min(per_payment 60000, daily 80000-30000, monthly 300000-30000)
        assertThat(view.get("sendableNowMinor").asLong()).isEqualTo(50000L);
        JsonNode daily = kind(view, "daily");
        assertThat(daily.get("remainingMinor").asLong()).isEqualTo(50000L);
        assertThat(daily.get("processingMinor").asLong()).isZero();
        assertThat(daily.get("resetsAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T00:00:00\\+03:00");
        assertThat(kind(view, "per_payment").has("resetsAt")).as("per_payment never resets").isFalse();
        assertThat(kind(view, "daily_count").get("remaining").asLong()).isEqualTo(4L);

        // One minor unit over what the screen offered: refused, with the same remainder...
        MvcResult over = transfer(50001, "agree-2");
        assertThat(over.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json(over);
        assertThat(body.get("limit").asText()).isEqualTo("daily");
        assertThat(body.get("remainingMinor").asLong()).isEqualTo(daily.get("remainingMinor").asLong());
        assertThat(body.get("resetsAt").asText()).isEqualTo(daily.get("resetsAt").asText());
        assertThat(body.get("kycTier").asText()).isEqualTo("basic");
        assertThat(body.get("nextTier").asText()).isEqualTo("standard");
        assertThat(body.get("remainingMinor").asLong()).isEqualTo(view.get("sendableNowMinor").asLong());

        // Over the per-payment cap (60000): it offers what passes now, the
        // screen's sendableNowMinor, not the cap.
        JsonNode perPayment = json(transfer(60001, "agree-pp"));
        assertThat(perPayment.get("limit").asText()).isEqualTo("per_payment");
        assertThat(perPayment.get("limitMinor").asLong()).isEqualTo(60000L);
        assertThat(perPayment.get("remainingMinor").asLong()).isEqualTo(view.get("sendableNowMinor").asLong());
        assertThat(perPayment.get("resetsAt").isNull()).isTrue();

        // ...and exactly what it offered passes the limits (and, being at the
        // step-up threshold, is then asked for its SMS approval — not refused).
        MvcResult atLimit = transfer(50000, "agree-3");
        assertThat(atLimit.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(atLimit).get("errorCode").asText()).isEqualTo("step_up_required");
    }

    @Test
    void aPaymentOverThePerPaymentLimitIsRefusedBeforeStepUpAndWritesNothing() throws Exception {
        MvcResult refused = transfer(70000, "pp-1");

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(refused).get("limit").asText()).isEqualTo("per_payment");
        assertThat(json(refused).get("limitMinor").asLong()).isEqualTo(60000L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isZero();
        assertThat(coreTransfers.get()).isZero();
    }

    @Test
    void aFailedPaymentStopsCountingAProcessingOneKeepsCounting() throws Exception {
        stubPort.onTransfer = (cmd, key) -> {
            throw new CoreClientException(CoreProvider.FINERACT, "Insufficient account balance.", null);
        };
        assertThat(transfer(30000, "fail-1").getResponse().getStatus()).isEqualTo(422);
        JsonNode afterFailure = limits();
        assertThat(kind(afterFailure, "daily").get("usedMinor").asLong()).isZero();
        assertThat(kind(afterFailure, "daily_count").get("used").asLong()).isZero();

        stubPort.onTransfer = (cmd, key) -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, cmd.externalRef(), "timeout", null);
        };
        MvcResult parked = transfer(20000, "unknown-1");
        assertThat(json(parked).get("status").asText()).isEqualTo("PROCESSING");
        JsonNode afterParked = limits();
        assertThat(kind(afterParked, "daily").get("usedMinor").asLong()).isEqualTo(20000L);
        assertThat(kind(afterParked, "daily").get("processingMinor").asLong()).isEqualTo(20000L);
        assertThat(kind(afterParked, "monthly").get("processingMinor").asLong()).isEqualTo(20000L);
        assertThat(kind(afterParked, "daily_count").get("used").asLong()).isEqualTo(1L);
    }

    @Test
    void theSixthPaymentOfTheDayIsRefusedOnTheCount() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(transfer(1000, "count-" + i).getResponse().getStatus()).isEqualTo(200);
        }
        MvcResult sixth = transfer(1000, "count-5");
        assertThat(sixth.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(sixth).get("limit").asText()).isEqualTo("daily_count");
        assertThat(json(sixth).get("limitCount").asLong()).isEqualTo(5L);
        assertThat(json(sixth).get("remainingCount").asLong()).isZero();
        assertThat(limits().get("sendableNowMinor").asLong()).isZero();
    }

    @Test
    void depositsAreNotLimited() throws Exception {
        mockMvc.perform(post("/transactions/deposit")
                        .header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", "dep-big")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","amountMinor":5000000,"currency":"KES"}
                                """.formatted(wallet)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(kind(limits(), "daily").get("usedMinor").asLong()).isZero();
    }

    @Test
    void theBootAuditRecordsThePolicyAndEveryOverride() {
        auditor.recordPolicyInForce();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata->>'sha256' FROM audit_event WHERE action = 'limits_policy_loaded'", String.class))
                .isEqualTo(policy.sha256());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'limits_override_active'", Integer.class))
                .isEqualTo(4);
        assertThat(policy.source()).isEqualTo("limits/limits-ke.yaml");
        assertThat(policy.limitsFor(KycTier.BASIC)).isEqualTo(new TierLimits(60000L, 80000L, 300000L, 5));
        assertThat(policy.limitsFor(KycTier.STANDARD).isDormant()).isTrue();
    }
}
