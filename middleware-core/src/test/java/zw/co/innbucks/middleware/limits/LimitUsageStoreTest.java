package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.middleware.common.country.Country;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The usage predicate and the lock, at the JDBC boundary. What the SQL DOES
 * against real rows is pinned in the app module's Postgres-backed
 * {@code PaymentLimitsIntegrationTest}; this pins WHAT it is asked — the
 * predicate's clauses and that every bound value is the one intended, in the
 * right slot.
 */
class LimitUsageStoreTest {

    private static final UUID CUSTOMER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LimitUsageStore store = new LimitUsageStore(jdbc);

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void thePredicateCountsOutgoingNonFailedRowsInTheCellCurrencyAndTheWindow() {
        String sql = LimitUsageStore.USAGE_SQL.replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("FROM ledger_transaction")
                .contains("WHERE customer_id = ?")
                .contains("AND type IN ('WITHDRAWAL', 'TRANSFER')")
                .contains("AND status <> 'FAILED'")
                .contains("AND currency = ?")
                .contains("AND created_at >= ? AND created_at < ?")
                // PROCESSING is every non-terminal status; COMPLETED is not in it.
                .contains("status IN ('PENDING', 'SUBMITTED', 'UNKNOWN')")
                .doesNotContain("DEPOSIT")
                .doesNotContain("'COMPLETED'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyWindowBoundIsBoundInItsSlot() {
        LimitWindows w = LimitWindows.at(Instant.parse("2026-10-28T08:00:00Z"), Country.ZW.zoneId());

        store.usage(CUSTOMER, "USD", w);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(eq(LimitUsageStore.USAGE_SQL), any(RowMapper.class), args.capture());
        Timestamp dayStart = Timestamp.from(Instant.parse("2026-10-27T22:00:00Z"));
        Timestamp dayEnd = Timestamp.from(Instant.parse("2026-10-28T22:00:00Z"));
        assertThat(args.getValue()).containsExactly(
                dayStart, dayEnd,          // day_used
                dayStart, dayEnd,          // day_processing
                dayStart, dayEnd,          // day_count
                CUSTOMER,
                "USD",
                Timestamp.from(Instant.parse("2026-09-30T22:00:00Z")),   // month start (1 Oct, Harare)
                Timestamp.from(Instant.parse("2026-10-31T22:00:00Z")));  // month end (1 Nov, Harare)
    }

    @Test
    void theSqlHasExactlyAsManyPlaceholdersAsBoundValues() {
        long placeholders = LimitUsageStore.USAGE_SQL.chars().filter(c -> c == '?').count();
        assertThat(placeholders).isEqualTo(10);
    }

    /** An advisory xact lock taken in autocommit is released at once and protects nothing. */
    @Test
    void theLockRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> store.lockCustomer(CUSTOMER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside the transaction");
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLockIsATransactionScopedAdvisoryLockKeyedByCustomer() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        store.lockCustomer(CUSTOMER);

        verify(jdbc).query(eq("SELECT pg_advisory_xact_lock(?, hashtext(?))"), any(ResultSetExtractor.class),
                eq(LimitUsageStore.LOCK_NAMESPACE), eq(CUSTOMER.toString()));
        assertThat(LimitUsageStore.LOCK_SQL).doesNotContain("FOR UPDATE");
    }

    @Test
    void theNamespaceIsAPositiveInt4() {
        // pg_advisory_xact_lock(int4, int4): the constant must fit.
        assertThat(LimitUsageStore.LOCK_NAMESPACE).isPositive();
    }
}
