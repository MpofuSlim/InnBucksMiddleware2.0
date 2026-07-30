package zw.co.innbucks.middleware.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import zw.co.innbucks.middleware.common.correlation.CorrelationContext;
import zw.co.innbucks.middleware.common.country.CountryProperties;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Every business event that matters for forensics or for monitoring goes
 * through {@link #record}. The audit trail is <b>tamper-evident</b>: each row
 * carries a keyed {@code row_hmac} over its content plus a {@code chain_hmac}
 * binding it to its predecessor (see {@link AuditHasher}); writes serialise on
 * the single-row {@code audit_chain_head} table locked {@code SELECT … FOR
 * UPDATE} inside a REQUIRES_NEW transaction, so two writers can never fork the
 * chain and an audit row survives a rolled-back business transaction (the
 * attempt trail is the point). {@link AuditIntegrityVerifier} walks the chain
 * nightly.
 *
 * <p>This middleware calls its core banking system with a service credential,
 * so the core attributes every transaction to that service account — this
 * chain, carrying the REAL customer identity per event, is the compensating
 * control. It is a launch dependency, not polish.
 *
 * <p>Observability (unchanged from the original design):
 * <ul>
 *   <li>{@code innbucks.audit.events} — tagged counter per action + outcome,
 *       e.g. {@code rate(innbucks_audit_events_total{action="login_failure"}[5m])}
 *       for the credential-stuffing dashboard.</li>
 *   <li>{@code innbucks.audit.write_failures} — audit failure must never break
 *       the user-facing flow, but must be loud in logs AND metrics.</li>
 * </ul>
 */
@Slf4j
@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final CountryProperties countryProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AuditHasher auditHasher;
    private final Clock clock;
    private final TransactionTemplate auditTransaction;
    private final Counter writeFailures;

    private static final String LOCK_HEAD_SQL = """
            SELECT chain_hmac FROM audit_chain_head WHERE id = 1 FOR UPDATE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO audit_event
                (occurred_at, country, customer_id, action, outcome, correlation_id,
                 ip_address, user_agent, device_hash, metadata, row_hmac, chain_hmac)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private static final String UPDATE_HEAD_SQL = """
            UPDATE audit_chain_head SET chain_hmac = ?, updated_at = ? WHERE id = 1
            """;

    public AuditService(JdbcTemplate jdbcTemplate,
                        CountryProperties countryProperties,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry,
                        AuditHasher auditHasher,
                        Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.countryProperties = countryProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.auditHasher = auditHasher;
        this.clock = clock;
        // REQUIRES_NEW: the chain write commits independently of the business
        // transaction — the head lock is held for microseconds, not for the
        // caller's whole transaction, and a rolled-back login still leaves its
        // failure row. Costs a second pooled connection while active.
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Single counter for the failure case — failures aren't tagged by
        // action because the SQL is identical, the failure cause isn't.
        this.writeFailures = Counter.builder("innbucks.audit.write_failures")
                .description("Audit-event INSERTs that threw (Postgres unreachable, schema drift, constraint violation, etc.)")
                .register(meterRegistry);
    }

    public void record(AuditAction action,
                       AuditOutcome outcome,
                       UUID customerId,
                       String deviceHash) {
        record(action, outcome, customerId, deviceHash, null);
    }

    public void record(AuditAction action,
                       AuditOutcome outcome,
                       UUID customerId,
                       String deviceHash,
                       Map<String, ?> metadata) {
        HttpServletRequest request = currentRequest();
        String ip = (request == null) ? null : clientIp(request);
        String userAgent = (request == null) ? null : request.getHeader("User-Agent");
        String correlationId = CorrelationContext.current();
        String metadataJson = auditHasher.canonicalizeJson(serializeMetadata(metadata));
        // Truncated to micros: Postgres timestamptz stores microsecond
        // precision, and the verifier re-hashes the value it reads back — any
        // sub-micro digits would self-inflict a false tamper alarm.
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String country = countryProperties.country().name();

        String rowHmac = auditHasher.rowHmac(occurredAt, country, customerId,
                action.dbValue(), outcome.dbValue(), correlationId, ip, userAgent,
                deviceHash, metadataJson);

        try {
            auditTransaction.executeWithoutResult(tx -> {
                String prev = jdbcTemplate.queryForObject(LOCK_HEAD_SQL, String.class);
                String chainHmac = auditHasher.chainHmac(prev, rowHmac);
                // metadataJson bound as Types.OTHER so PostgreSQL can resolve
                // the ?::jsonb cast even when the value is null.
                jdbcTemplate.update(INSERT_SQL,
                        Timestamp.from(occurredAt),
                        country,
                        customerId,
                        action.dbValue(),
                        outcome.dbValue(),
                        correlationId,
                        ip,
                        userAgent,
                        deviceHash,
                        new SqlParameterValue(Types.OTHER, metadataJson),
                        rowHmac,
                        chainHmac);
                jdbcTemplate.update(UPDATE_HEAD_SQL, chainHmac, Timestamp.from(occurredAt));
            });

            // Increment AFTER the insert succeeds so the counter and the
            // audit_event table agree. Cardinality is bounded by AuditAction's
            // enum count.
            actionCounter(action, outcome).increment();
        } catch (Exception ex) {
            // Audit failure must never break the user-facing flow, but must be
            // loud in BOTH logs and metrics.
            writeFailures.increment();
            log.error("Failed to persist audit event action={} customerId={} correlation={}",
                    action, customerId, correlationId, ex);
        }
    }

    private Counter actionCounter(AuditAction action, AuditOutcome outcome) {
        return Counter.builder("innbucks.audit.events")
                .description("Business events recorded to audit_event, tagged by action + outcome")
                .tag("action", action.dbValue())
                .tag("outcome", outcome.dbValue())
                .register(meterRegistry);
    }

    private String serializeMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialise audit metadata; dropping field but writing the row", ex);
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0) ? forwarded.trim() : forwarded.substring(0, comma).trim();
        }
        return request.getRemoteAddr();
    }
}
