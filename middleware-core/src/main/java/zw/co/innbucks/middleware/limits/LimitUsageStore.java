package zw.co.innbucks.middleware.limits;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * The ONE usage query and the per-customer reservation lock. The advisory
 * pre-check, the binding check and {@code GET /me/limits} all read usage
 * through {@link #usage}, so the screen and the refusal can never disagree
 * about what counts.
 *
 * <p>What counts — the predicate, in one place:
 * <ul>
 *   <li>{@code type IN ('WITHDRAWAL','TRANSFER')} — deposits are not limited;</li>
 *   <li>{@code status <> 'FAILED'} — SUCCESS and PROCESSING (PENDING,
 *       SUBMITTED, UNKNOWN) count; a payment that later FAILS stops counting
 *       with no release step to forget;</li>
 *   <li>{@code currency = <cell currency>};</li>
 *   <li>{@code created_at} inside the window it was MADE in.</li>
 * </ul>
 * Rows exist only for movements made through this middleware — the limits
 * are an APP-channel limit by construction.
 */
@Component
public class LimitUsageStore {

    /**
     * First key of the two-int advisory lock: namespaces this lock family
     * ("ILMT"). Postgres keeps the (int4, int4) key space apart from the
     * single-bigint one Flyway uses, and nothing else here takes advisory
     * locks. The second key is {@code hashtext(customer_id::text)}; two
     * customers whose hashes collide merely queue behind each other briefly.
     */
    static final int LOCK_NAMESPACE = 0x494C4D54;

    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

    static final String USAGE_SQL = """
            SELECT COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ? AND created_at < ?), 0) AS day_used,
                   COALESCE(SUM(amount_minor) FILTER (WHERE created_at >= ? AND created_at < ?
                                                        AND status IN ('PENDING', 'SUBMITTED', 'UNKNOWN')), 0)
                                                                                         AS day_processing,
                   COUNT(*) FILTER (WHERE created_at >= ? AND created_at < ?)            AS day_count,
                   COALESCE(SUM(amount_minor), 0)                                        AS month_used,
                   COALESCE(SUM(amount_minor) FILTER (WHERE status IN ('PENDING', 'SUBMITTED', 'UNKNOWN')), 0)
                                                                                         AS month_processing
              FROM ledger_transaction
             WHERE customer_id = ?
               AND type IN ('WITHDRAWAL', 'TRANSFER')
               AND status <> 'FAILED'
               AND currency = ?
               AND created_at >= ?
               AND created_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public LimitUsageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Serialise this customer's limit reservations until the CURRENT
     * transaction ends. {@code pg_advisory_xact_lock} rather than a row lock
     * on {@code customer}: it contends with nothing else — not login's counter
     * UPDATE, not a PIN reset, and not the {@code FOR KEY SHARE} that every
     * ledger / refresh-token INSERT takes on the customer row (which is why a
     * customer-row lock would have to be {@code FOR NO KEY UPDATE}, never
     * {@code FOR UPDATE}).
     *
     * <p>Outside a transaction the lock would be released the instant the
     * statement auto-commits and protect nothing, so that is refused loudly.
     */
    public void lockCustomer(UUID customerId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The limit reservation lock must be taken inside the "
                    + "transaction that inserts the PENDING ledger row");
        }
        jdbcTemplate.query(LOCK_SQL, (ResultSetExtractor<Void>) rs -> null,
                LOCK_NAMESPACE, customerId.toString());
    }

    /**
     * Usage in the windows' day and month. On the binding path this runs
     * after {@link #lockCustomer} in the same READ COMMITTED transaction, so
     * it sees every reservation committed by the previous lock holder.
     */
    public LimitUsage usage(UUID customerId, String currency, LimitWindows windows) {
        Timestamp dayStart = Timestamp.from(windows.dayStart());
        Timestamp dayEnd = Timestamp.from(windows.dayEnd());
        return jdbcTemplate.queryForObject(USAGE_SQL,
                (rs, n) -> new LimitUsage(
                        rs.getLong("day_used"),
                        rs.getLong("day_processing"),
                        rs.getLong("day_count"),
                        rs.getLong("month_used"),
                        rs.getLong("month_processing")),
                dayStart, dayEnd,
                dayStart, dayEnd,
                dayStart, dayEnd,
                customerId,
                currency,
                Timestamp.from(windows.monthStart()),
                Timestamp.from(windows.monthEnd()));
    }
}
