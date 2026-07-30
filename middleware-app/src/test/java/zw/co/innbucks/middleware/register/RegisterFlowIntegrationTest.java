package zw.co.innbucks.middleware.register;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full registration flow against real Postgres with a stub core: creation +
 * wallet saga + core mapping, idempotent replay, key-conflict, duplicate
 * MSISDN, and the crashed-registration resume path.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, RegisterFlowIntegrationTest.StubConfig.class})
class RegisterFlowIntegrationTest {

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

    MockMvc mockMvc;

    final AtomicInteger coreCreates = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE idempotency_record, ledger_transaction_event, ledger_transaction, "
                + "audit_event, refresh_token, customer CASCADE");
        coreCreates.set(0);
        stubPort.onCreateCustomer = (cmd, key) -> {
            coreCreates.incrementAndGet();
            return new CoreCustomerRef(cmd.requestedExternalId());
        };
        stubPort.onOpenDepositAccount = (customer, extId, key) -> new AccountRef(extId);
    }

    private static String body(String msisdn) {
        return """
                {"msisdn":"%s","firstName":"Tariro","lastName":"Moyo","nationalId":"12345678"}
                """.formatted(msisdn);
    }

    @Test
    void registersCustomerWithWalletAndCoreMapping() throws Exception {
        mockMvc.perform(post("/register")
                        .header("Idempotency-Key", "reg-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("0712345678")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_verification"))
                .andExpect(jsonPath("$.walletAccountId").value(org.hamcrest.Matchers.endsWith(":wallet")));

        // Row is fully mapped: normalized MSISDN, core ids, HMAC'd national id, no PIN yet.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT msisdn, core_provider, core_external_id, national_id_hash, pin_hash, status "
                        + "FROM customer"))
                .containsEntry("msisdn", "+254712345678")
                .containsEntry("core_provider", "FINERACT")
                .containsEntry("status", "pending_verification")
                .satisfies(m -> {
                    assertThat(m.get("core_external_id")).isNotNull();
                    assertThat(m.get("national_id_hash")).isNotNull();
                    assertThat(m.get("pin_hash")).isNull();
                });
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'register_success'", Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void sameKeySameBodyReplaysWithoutASecondCoreCall() throws Exception {
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345679")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345679")))
                .andExpect(status().isCreated());

        assertThat(coreCreates.get()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customer", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsAConflict() throws Exception {
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-3")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345680")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"0712345680","firstName":"DIFFERENT","lastName":"Moyo"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("idempotency_conflict"));
    }

    @Test
    void registeredMsisdnIsRejectedWithConflict() throws Exception {
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-4")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345681")))
                .andExpect(status().isCreated());
        // A NEW key for an already-registered MSISDN is a genuine duplicate signup.
        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-5")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345681")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("customer_already_registered"));
    }

    @Test
    void crashedRegistrationResumesOnTheExistingRow() throws Exception {
        // A row without core_external_id = an earlier attempt died mid-saga.
        UUID orphanId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer (id, country, msisdn, pin_hash, kyc_tier, core_provider,
                                      status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', '+254712345682', NULL, 'basic', 'FINERACT',
                        'pending_verification', 0, ?, ?)
                """, orphanId, Timestamp.from(now), Timestamp.from(now));

        mockMvc.perform(post("/register").header("Idempotency-Key", "reg-key-6")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345682")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(orphanId.toString()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT core_external_id FROM customer WHERE id = ?", String.class, orphanId))
                .isEqualTo(orphanId.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customer", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body("0712345683")))
                .andExpect(status().isBadRequest());
    }
}
