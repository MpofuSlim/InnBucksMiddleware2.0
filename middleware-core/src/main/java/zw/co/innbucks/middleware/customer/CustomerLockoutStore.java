package zw.co.innbucks.middleware.customer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The two statements that move a customer's brute-force lockout state, each a
 * SINGLE self-atomic SQL statement.
 *
 * <p><b>Neither method may EVER be given {@code @Transactional} (or a
 * TransactionTemplate).</b> A lone JDBC statement is already atomic in
 * Postgres, so an annotation buys no atomicity — and it costs an eager
 * {@code DataSourceTransactionManager.doBegin() -> getConnection()} at the
 * interceptor boundary, re-pinning a pooled connection on the very path this
 * class exists to un-pin. See {@code LoginService.login}'s javadoc for the bug
 * that motivated all of this.
 *
 * <p><b>Why a separate bean rather than private helpers on LoginService.</b>
 * Partly the above — an annotation added here would at least be real. A
 * {@code @Transactional} private helper called from inside {@code login()}
 * goes through {@code this}, bypasses the Spring proxy, and is silently
 * DECORATIVE: no transaction, no error, no failing test.
 *
 * <p><b>The increment is computed server-side, and that is the point.</b> The
 * counter used to be a read-modify-write (load the row, {@code +1} in Java,
 * {@code save()}), so two racing wrong PINs both read N and both wrote N+1 —
 * the attacker got free attempts and the lockout ladder was silently skipped.
 * {@code failed_pin_attempts + 1} inside the UPDATE cannot lose an update: a
 * concurrent writer of the same row blocks on the row lock and then
 * re-evaluates against the newly committed tuple.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerLockoutStore {

    /**
     * <b>The guard reads {@code failed_pin_attempts + 1 >= ?} and must NEVER be
     * simplified to {@code failed_pin_attempts >= ?}.</b> Postgres evaluates
     * every right-hand side in a SET list against the OLD row, simultaneously —
     * a CASE cannot see a column assigned earlier in the same list. The short
     * spelling compares the pre-increment count and silently moves the lock
     * from the 7th wrong PIN to the 8th. It compiles, it reads plausibly, and
     * it passes every pre-existing test;
     * {@code CustomerLockoutStoreIntegrationTest.theCapTransitionIsAtomicWithTheIncrement}
     * is the only thing that discriminates the two.
     *
     * <p>The {@code ELSE status} / {@code ELSE locked_until} arms re-assign the
     * old value — a no-op preserve, so the row is never left holding half a
     * lock.
     *
     * <p>WHERE is the primary key ALONE, deliberately. Under READ COMMITTED a
     * blocked concurrent UPDATE re-evaluates the WHERE clause against the
     * newly-committed row, so a guard predicate could turn false on re-check
     * and silently drop the attempt (0 rows, empty RETURNING, no way to say
     * why). The ladder belongs in the CASE, which always updates and always
     * returns a row.
     */
    private static final String RECORD_FAILED_ATTEMPT_SQL = """
            UPDATE customer
               SET failed_pin_attempts = failed_pin_attempts + 1,
                   last_failed_pin_at  = ?,
                   updated_at          = ?,
                   status              = CASE WHEN failed_pin_attempts + 1 >= ?
                                              THEN ?
                                              ELSE status END,
                   locked_until        = CASE WHEN failed_pin_attempts + 1 >= ?
                                              THEN ?
                                              ELSE locked_until END
             WHERE id = ?
            RETURNING failed_pin_attempts
            """;

    /**
     * Deliberately NARROW — it names only the five columns login owns. The
     * {@code save()} it replaces emitted a full-row UPDATE built from the
     * snapshot read before the PIN was even hashed, with no version predicate,
     * so a successful login could revert a concurrent PIN reset's
     * {@code pin_hash} or blank a concurrent registration's
     * {@code core_external_id}.
     *
     * <p>The status CASE is not garnish. This statement sets
     * {@code locked_until = NULL}; leaving {@code status = 'locked'} beside a
     * NULL {@code locked_until} manufactures a row state no single writer
     * produces — inert for the auth gate (which requires both) but echoed raw
     * to a fully working customer by {@code GET /me/profile}. Clearing a lock
     * clears BOTH halves. Guarded rather than an unconditional
     * {@code status = 'active'} so it can only ever move 'locked' -> 'active'
     * and can never widen the vocabulary.
     */
    private static final String CLEAR_FAILURE_COUNTERS_SQL = """
            UPDATE customer
               SET failed_pin_attempts = 0,
                   last_failed_pin_at  = NULL,
                   locked_until        = NULL,
                   status              = CASE WHEN status = ? THEN ? ELSE status END,
                   updated_at          = ?
             WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Count one failed PIN attempt and, at the cap, lock the account — in one
     * statement, so the count and the lock transition can never disagree.
     *
     * @return the POST-increment attempt count, or empty if the row no longer
     *         exists. Empty is data, not an exception: the credentials were
     *         still wrong, and a 500 here would be both a worse answer and a
     *         new response-shape oracle.
     */
    public OptionalInt recordFailedAttempt(UUID customerId, Instant failedAt,
                                           int maxAttemptsBeforeLock, Duration lockDuration) {
        // query(), not queryForObject(): the latter throws
        // EmptyResultDataAccessException when the UPDATE matches zero rows.
        List<Integer> rows = jdbcTemplate.query(RECORD_FAILED_ATTEMPT_SQL,
                (rs, rowNum) -> rs.getInt("failed_pin_attempts"),
                Timestamp.from(failedAt),                       // last_failed_pin_at
                Timestamp.from(failedAt),                       // updated_at
                maxAttemptsBeforeLock,                          // status CASE guard
                CustomerStatus.LOCKED.dbValue(),                // status when locking
                maxAttemptsBeforeLock,                          // locked_until CASE guard
                Timestamp.from(failedAt.plus(lockDuration)),    // locked_until when locking
                customerId);
        // The per-argument comments above are load-bearing: the two
        // Timestamp.from(failedAt) args and the two maxAttemptsBeforeLock args
        // are same-typed pairs whose transposition would be invisible.
        return rows.isEmpty() ? OptionalInt.empty() : OptionalInt.of(rows.get(0));
    }

    /**
     * Clear the failure counters after a correct PIN, releasing an expired lock
     * if one was still recorded.
     *
     * @return whether the row was found and updated.
     */
    public boolean clearFailureCounters(UUID customerId, Instant now) {
        return jdbcTemplate.update(CLEAR_FAILURE_COUNTERS_SQL,
                CustomerStatus.LOCKED.dbValue(),
                CustomerStatus.ACTIVE.dbValue(),
                Timestamp.from(now),
                customerId) == 1;
    }
}
