package zw.co.innbucks.middleware.auth.login;

import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import zw.co.innbucks.middleware.auth.pin.PinHasher;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.customer.CustomerStatus;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.support.PostgresTestContainer;
import zw.co.innbucks.middleware.support.SettableCorePort;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A FULL bank block on the wallet ends the customer's sessions at the next
 * refresh — every device's, not just the one asking — while a debit-only
 * block, a core outage, a SLOW core and a customer with no core mapping all
 * keep the session. The core is read while the presented token is still
 * unspent, so a slow core can never cost a client its session. Real Postgres
 * (refresh_token rows, audit chain), real controller and guard; the core is
 * the settable stub. The read budget is shortened so the slow case is quick.
 */
@SpringBootTest(properties = "innbucks.auth.refresh-block-check.timeout=300ms")
@Import({PostgresTestContainer.class, RefreshAccountBlockIntegrationTest.StubConfig.class})
class RefreshAccountBlockIntegrationTest {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        SettableCorePort settableCorePort() {
            return new SettableCorePort();
        }
    }

    private static final String MSISDN = "+254712345678";
    private static final String PHONE = "device-phone";
    private static final String TABLET = "device-tablet";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PinHasher pinHasher;
    @Autowired LoginService loginService;
    @Autowired SettableCorePort stubPort;
    @Autowired MeterRegistry meterRegistry;

    MockMvc mockMvc;
    UUID customerId;
    final AtomicInteger listingReads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbcTemplate.update("TRUNCATE refresh_token, audit_event, customer CASCADE");
        customerId = UUID.randomUUID();
        insertCustomer(customerId, customerId.toString());
        listingReads.set(0);
    }

    private void insertCustomer(UUID id, String coreExternalId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, 'KE', ?, ?, ?, 'FINERACT', ?, ?, 0, ?, ?)
                """,
                id, MSISDN, pinHasher.hash("1234"), KycTier.BASIC.dbValue(), coreExternalId,
                CustomerStatus.ACTIVE.dbValue(), Timestamp.from(now), Timestamp.from(now));
    }

    private void walletIs(AccountRestriction restriction) {
        stubPort.onListAccounts = ref -> {
            listingReads.incrementAndGet();
            return List.of(new DepositAccountSummary(new AccountRef(ref.externalId() + ":wallet"),
                    "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES"), restriction));
        };
    }

    private String login(String device) {
        return loginService.login("0712345678", "1234", device).refreshToken();
    }

    private ResultActions refresh(String refreshToken, String device) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s","deviceHash":"%s"}
                        """.formatted(refreshToken, device)));
    }

    /**
     * Tokens that would still refresh: unrevoked AND not yet rotated away. A
     * rotation marks its parent {@code replaced_by_jti} and leaves
     * {@code revoked_at} NULL, so counting unrevoked rows alone would count a
     * refreshed session twice.
     */
    private int liveRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ? AND revoked_at IS NULL "
                        + "AND replaced_by_jti IS NULL",
                Integer.class, customerId);
    }

    private int rotatedRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ? AND replaced_by_jti IS NOT NULL",
                Integer.class, customerId);
    }

    private double checks(String outcome) {
        return meterRegistry.counter("innbucks.auth.refresh.block_check", "outcome", outcome).count();
    }

    @Test
    void aFullBlockRefusesTheRefreshAndEndsEverySessionOnEveryDevice() throws Exception {
        String phone = login(PHONE);
        String tablet = login(TABLET);
        walletIs(AccountRestriction.FULLY_BLOCKED);

        refresh(phone, PHONE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("account_restricted"))
                .andExpect(jsonPath("$.detail").value(
                        "Your account has been restricted and you have been signed out. Please contact support."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // Every family — the tablet's too. Nothing was rotated: the check runs
        // before the token is spent, so the only rows are the two logins'.
        assertThat(liveRows()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE customer_id = ?", Integer.class, customerId))
                .isEqualTo(2);
        assertThat(rotatedRows()).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT revocation_reason FROM refresh_token WHERE customer_id = ? "
                        + "AND revocation_reason IS NOT NULL", String.class, customerId))
                .containsExactly("account_blocked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata->>'refreshTokensRevoked' FROM audit_event "
                        + "WHERE action = 'refresh_blocked_account_restricted' AND customer_id = ?",
                String.class, customerId)).isNotNull().isNotEqualTo("0");

        // The other device is signed out too: its token is revoked (an
        // ordinary invalid refresh, never mistaken for a replay).
        refresh(tablet, TABLET)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("refresh_invalid"));
    }

    @Test
    void aDebitOnlyBlockKeepsTheSession() throws Exception {
        String phone = login(PHONE);
        walletIs(AccountRestriction.DEBITS_BLOCKED);

        refresh(phone, PHONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(liveRows()).isEqualTo(1);
        assertThat(listingReads.get()).isEqualTo(1);
    }

    @Test
    void aCoreOutageFailsOpenAndIsCounted() throws Exception {
        String phone = login(PHONE);
        double before = checks("read_failed");
        stubPort.onListAccounts = ref -> {
            throw new CoreTransientException(CoreProvider.FINERACT, "Fineract unreachable", null);
        };

        refresh(phone, PHONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(liveRows()).isEqualTo(1);
        assertThat(checks("read_failed")).isEqualTo(before + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'refresh_blocked_account_restricted'",
                Integer.class)).isZero();
    }

    @Test
    void theCoreIsReadWhileThePresentedTokenIsStillUnspent() throws Exception {
        // The property that keeps a slow core from signing anyone out: if the
        // client gives up waiting, the token it holds still works. Spent
        // before the read, its successor would be lost with the response and
        // the next refresh would read as a replay that revokes the family.
        String phone = login(PHONE);
        AtomicInteger rotatedDuringRead = new AtomicInteger(-1);
        stubPort.onListAccounts = ref -> {
            rotatedDuringRead.set(rotatedRows());
            return List.of(new DepositAccountSummary(new AccountRef(ref.externalId() + ":wallet"),
                    "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES"), AccountRestriction.NONE));
        };

        refresh(phone, PHONE).andExpect(status().isOk());

        assertThat(rotatedDuringRead).hasValue(0);
        assertThat(rotatedRows()).isEqualTo(1);
    }

    @Test
    void aSlowCoreNeitherHoldsTheRefreshNorSignsAnyoneOut() throws Exception {
        // Fineract accepting connections but not answering (the adapter would
        // retry a 10 s read three times). The refresh is answered at the
        // budget, unblocked; the read is abandoned, not interrupted.
        String phone = login(PHONE);
        double before = checks("timed_out");
        CountDownLatch release = new CountDownLatch(1);
        stubPort.onListAccounts = ref -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return List.of(new DepositAccountSummary(new AccountRef(ref.externalId() + ":wallet"),
                    "InnBucks Wallet", "KES", new MinorUnits(150000L, "KES"), AccountRestriction.FULLY_BLOCKED));
        };
        try {
            long started = System.nanoTime();
            refresh(phone, PHONE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }

        assertThat(checks("timed_out")).isEqualTo(before + 1);
        assertThat(liveRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'refresh_blocked_account_restricted'",
                Integer.class)).isZero();
    }

    @Test
    void aCustomerWithNoCoreMappingIsNeverLookedUp() throws Exception {
        jdbcTemplate.update("UPDATE customer SET core_external_id = NULL WHERE id = ?", customerId);
        String phone = login(PHONE);
        walletIs(AccountRestriction.FULLY_BLOCKED);

        refresh(phone, PHONE).andExpect(status().isOk());

        assertThat(listingReads.get()).isZero();
    }

    @Test
    void afterTheBankLiftsTheBlockTheCustomerSignsInAgainAsNormal() throws Exception {
        String phone = login(PHONE);
        walletIs(AccountRestriction.FULLY_BLOCKED);
        refresh(phone, PHONE).andExpect(status().isForbidden());

        walletIs(AccountRestriction.NONE);
        String fresh = login(PHONE);

        refresh(fresh, PHONE).andExpect(status().isOk());
    }
}
