package zw.co.innbucks.middleware.ledger;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends CrudRepository<LedgerTransaction, UUID> {

    @Query("SELECT * FROM ledger_transaction WHERE external_ref = :externalRef")
    Optional<LedgerTransaction> findByExternalRef(@Param("externalRef") String externalRef);

    /**
     * Rows the reconciler owes a poll: parked (UNKNOWN) or in-flight
     * (SUBMITTED), whose backoff deadline has passed (or was never set).
     * Oldest first so nothing starves behind a hot new row.
     */
    @Query("""
            SELECT * FROM ledger_transaction
             WHERE status IN ('UNKNOWN', 'SUBMITTED')
               AND (next_reconcile_at IS NULL OR next_reconcile_at <= :now)
             ORDER BY created_at ASC
             LIMIT :limit
            """)
    List<LedgerTransaction> findDueForReconciliation(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * PENDING rows older than the staleness cutoff: the process died between
     * opening the row and recording the call's outcome. The call MAY have
     * been sent — these are parked as UNKNOWN, never auto-failed.
     */
    @Query("""
            SELECT * FROM ledger_transaction
             WHERE status = 'PENDING' AND created_at < :cutoff
             ORDER BY created_at ASC
             LIMIT :limit
            """)
    List<LedgerTransaction> findStalePending(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Operator-alarm feed: parked rows nobody has resolved for too long. */
    @Query("""
            SELECT COUNT(*) FROM ledger_transaction
             WHERE status = 'UNKNOWN' AND created_at < :cutoff
            """)
    long countParkedOlderThan(@Param("cutoff") Instant cutoff);
}
