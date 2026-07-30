package zw.co.innbucks.middleware.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Nightly walk of the audit chain, oldest-first, recomputing every
 * {@code row_hmac} and every {@code chain_hmac} link (plus the
 * {@code audit_chain_head} pointer at the end). Two failure modes, two
 * metrics, two severities:
 *
 * <ul>
 *   <li>{@code innbucks.audit.integrity.broken} — a row's CONTENT no longer
 *       matches its {@code row_hmac}: someone edited an audit row. Page.</li>
 *   <li>{@code innbucks.audit.chain.broken} — the links don't join up: a row
 *       was deleted/reordered, or the HMAC key rotated without re-anchoring.
 *       Surviving content still self-verifies, and a benign key rotation
 *       looks identical, so: ticket, not page.</li>
 * </ul>
 */
@Slf4j
@Component
public class AuditIntegrityVerifier {

    private static final int BATCH_SIZE = 1000;

    private static final String SELECT_BATCH_SQL = """
            SELECT id, occurred_at, country, customer_id, action, outcome, correlation_id,
                   ip_address, user_agent, device_hash, metadata::text AS metadata_text,
                   row_hmac, chain_hmac
              FROM audit_event
             WHERE id > ?
             ORDER BY id ASC
             LIMIT %d
            """.formatted(BATCH_SIZE);

    private static final String SELECT_HEAD_SQL =
            "SELECT chain_hmac FROM audit_chain_head WHERE id = 1";

    private final JdbcTemplate jdbcTemplate;
    private final AuditHasher auditHasher;
    private final Counter contentBroken;
    private final Counter chainBroken;

    public AuditIntegrityVerifier(JdbcTemplate jdbcTemplate,
                                  AuditHasher auditHasher,
                                  MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditHasher = auditHasher;
        this.contentBroken = Counter.builder("innbucks.audit.integrity.broken")
                .description("audit_event rows whose content no longer matches row_hmac (content tampering — page)")
                .register(meterRegistry);
        this.chainBroken = Counter.builder("innbucks.audit.chain.broken")
                .description("audit_event chain links that fail to verify (deletion/reorder or key rotation — ticket)")
                .register(meterRegistry);
    }

    @Scheduled(cron = "${innbucks.audit.verify-cron:0 45 3 * * *}")
    public void verifyChain() {
        long lastId = 0;
        long rows = 0;
        long contentFailures = 0;
        long chainFailures = 0;
        String prevChain = null; // genesis predecessor is the empty string inside chainHmac()
        String lastChain = null;

        while (true) {
            List<Row> batch = jdbcTemplate.query(SELECT_BATCH_SQL, (rs, n) -> new Row(
                    rs.getLong("id"),
                    rs.getTimestamp("occurred_at").toInstant().truncatedTo(ChronoUnit.MICROS),
                    rs.getString("country"),
                    (UUID) rs.getObject("customer_id"),
                    rs.getString("action"),
                    rs.getString("outcome"),
                    rs.getString("correlation_id"),
                    rs.getString("ip_address"),
                    rs.getString("user_agent"),
                    rs.getString("device_hash"),
                    rs.getString("metadata_text"),
                    rs.getString("row_hmac"),
                    rs.getString("chain_hmac")), lastId);
            if (batch.isEmpty()) {
                break;
            }
            for (Row row : batch) {
                rows++;
                String expectedRow = auditHasher.rowHmac(row.occurredAt(), row.country(),
                        row.customerId(), row.action(), row.outcome(), row.correlationId(),
                        row.ipAddress(), row.userAgent(), row.deviceHash(),
                        auditHasher.canonicalizeJson(row.metadataText()));
                if (!expectedRow.equals(row.rowHmac())) {
                    contentFailures++;
                    contentBroken.increment();
                    log.error("AUDIT CONTENT TAMPER: audit_event id={} row_hmac mismatch", row.id());
                }
                String expectedChain = auditHasher.chainHmac(prevChain, row.rowHmac());
                if (!expectedChain.equals(row.chainHmac())) {
                    chainFailures++;
                    chainBroken.increment();
                    log.error("AUDIT CHAIN BREAK: audit_event id={} chain_hmac does not bind to predecessor "
                            + "(row deleted/reordered before it, or HMAC key rotated)", row.id());
                }
                prevChain = row.chainHmac();
                lastChain = row.chainHmac();
                lastId = row.id();
            }
        }

        if (rows > 0) {
            String head = jdbcTemplate.queryForObject(SELECT_HEAD_SQL, String.class);
            if (head == null || !head.equals(lastChain)) {
                chainFailures++;
                chainBroken.increment();
                log.error("AUDIT CHAIN BREAK: audit_chain_head does not match the last row's chain_hmac "
                        + "(tail truncation, or writes lost after head update)");
            }
        }

        if (contentFailures == 0 && chainFailures == 0) {
            log.info("Audit chain verified clean: {} row(s)", rows);
        } else {
            log.error("Audit chain verification FAILED: {} row(s), {} content failure(s), {} chain failure(s)",
                    rows, contentFailures, chainFailures);
        }
    }

    private record Row(long id, Instant occurredAt, String country, UUID customerId,
                       String action, String outcome, String correlationId, String ipAddress,
                       String userAgent, String deviceHash, String metadataText,
                       String rowHmac, String chainHmac) {
    }
}
