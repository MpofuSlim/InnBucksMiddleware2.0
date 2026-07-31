package zw.co.innbucks.middleware.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.correlation.CorrelationContext;
import zw.co.innbucks.middleware.notify.txn.SettledMovementEvent;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ledger writes — the single lifecycle chokepoint for money movement, ported
 * from the proven ticketing payment-service design. Each method commits in
 * its OWN transaction ({@link Propagation#REQUIRES_NEW}) so the PENDING row
 * is durable before any core call, and a downstream failure or local
 * rollback cannot disappear the trail.
 *
 * <p>Banking-grade invariants enforced here:
 * <ul>
 *   <li><b>Write-ahead:</b> {@link #openPending} MUST commit before the core
 *       write is invoked — an upstream success with no local row is the worst
 *       class of bug in a money system.</li>
 *   <li><b>Journalled transitions:</b> every status change appends a
 *       {@code ledger_transaction_event} row IN THE SAME TRANSACTION as the
 *       change, so the ledger and its history cannot diverge.</li>
 *   <li><b>Guarded transitions:</b> {@link #LEGAL_TRANSITIONS} is the only
 *       path between states. Terminal rows (COMPLETED / FAILED) are
 *       immutable; an illegal request is logged loudly, counted
 *       ({@code innbucks.ledger.illegal_transitions}) and SKIPPED — never
 *       applied, and never thrown either, because masking the upstream
 *       outcome from the caller is worse than refusing a bookkeeping write.</li>
 *   <li><b>No guessing:</b> UNKNOWN exists precisely so a timeout is never
 *       recorded as FAILED. Only a reconciler that has QUERIED the core (or
 *       an operator) moves a row out of UNKNOWN.</li>
 * </ul>
 *
 * <p>Money-significant transitions additionally seal a tamper-evident
 * {@link AuditService} row (the chained-HMAC trail) carrying the REAL
 * customer identity — the compensating control for the core attributing
 * every write to the middleware's service account.
 */
@Slf4j
@Service
public class LedgerService {

    /** The complete legal state machine. Anything not listed is refused. */
    private static final Map<LedgerStatus, Set<LedgerStatus>> LEGAL_TRANSITIONS = Map.of(
            LedgerStatus.PENDING, EnumSet.of(
                    LedgerStatus.SUBMITTED, LedgerStatus.COMPLETED,
                    LedgerStatus.FAILED, LedgerStatus.UNKNOWN),
            LedgerStatus.SUBMITTED, EnumSet.of(
                    LedgerStatus.COMPLETED, LedgerStatus.FAILED, LedgerStatus.UNKNOWN),
            LedgerStatus.UNKNOWN, EnumSet.of(
                    LedgerStatus.COMPLETED, LedgerStatus.FAILED, LedgerStatus.SUBMITTED)
    );

    private static final String INSERT_SQL = """
            INSERT INTO ledger_transaction
                (id, customer_id, type, source_account, destination_account, amount_minor,
                 currency, narrative, external_ref, core_provider, status, correlation_id,
                 created_at, updated_at, reconcile_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO ledger_transaction_event
                (transaction_id, from_status, to_status, detail, correlation_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String RECONCILE_ATTEMPT_SQL = """
            UPDATE ledger_transaction
               SET reconcile_attempts = reconcile_attempts + 1,
                   next_reconcile_at = ?,
                   updated_at = ?
             WHERE id = ?
            """;

    private final LedgerTransactionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Counter illegalTransitions;

    public LedgerService(LedgerTransactionRepository repository,
                         JdbcTemplate jdbcTemplate,
                         AuditService auditService,
                         MeterRegistry meterRegistry,
                         ApplicationEventPublisher events,
                         Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
        this.events = events;
        this.clock = clock;
        this.illegalTransitions = Counter.builder("innbucks.ledger.illegal_transitions")
                .description("Refused ledger state transitions — two code paths disagreeing about an outcome; an operator must look")
                .register(meterRegistry);
    }

    /**
     * Open the write-ahead PENDING row. MUST be committed (REQUIRES_NEW does
     * that on return) before the core call is made. INSERT goes through
     * JdbcTemplate — client-assigned {@code @Id}, the save()-emits-UPDATE
     * gotcha.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerTransaction openPending(LedgerDraft draft) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String correlationId = CorrelationContext.current();
        jdbcTemplate.update(INSERT_SQL,
                id,
                draft.customerId(),
                draft.type().name(),
                draft.sourceAccount(),
                draft.destinationAccount(),
                draft.amount().amount(),
                draft.amount().currencyCode(),
                draft.narrative(),
                draft.externalRef(),
                draft.coreProvider().name(),
                LedgerStatus.PENDING.dbValue(),
                correlationId,
                Timestamp.from(now),
                Timestamp.from(now));
        recordEvent(id, null, LedgerStatus.PENDING,
                "Ledger row opened before core call", correlationId, now);
        log.info("ledger PENDING id={} externalRef={} type={} amountMinor={} {} customerId={}",
                id, draft.externalRef(), draft.type(), draft.amount().amount(),
                draft.amount().currencyCode(), draft.customerId());
        return repository.findById(id).orElseThrow();
    }

    /** The core POSITIVELY accepted the write and is processing it asynchronously. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmitted(UUID id, String coreTxRef) {
        transition(id, LedgerStatus.SUBMITTED, "Core accepted the write; processing", tx -> {
            if (coreTxRef != null) {
                tx.setCoreTxRef(truncate(coreTxRef, 128));
            }
        });
    }

    /** Terminal success: the core confirmed the movement applied. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID id, String coreTxRef, String detail) {
        transition(id, LedgerStatus.COMPLETED, detail, tx -> {
            if (coreTxRef != null) {
                tx.setCoreTxRef(truncate(coreTxRef, 128));
            }
            tx.setCompletedAt(clock.instant());
        });
    }

    /**
     * Terminal failure: the core POSITIVELY reported the movement did not
     * apply (rejection, or definitively-never-received). NEVER call this on
     * an ambiguous outcome — that is {@link #markUnknown}'s job.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String failureCode, String failureMessage) {
        transition(id, LedgerStatus.FAILED, "Core reported failure: " + failureCode, tx -> {
            tx.setFailureCode(truncate(failureCode, 64));
            tx.setFailureMessage(truncate(failureMessage, 500));
            tx.setCompletedAt(clock.instant());
        });
    }

    /**
     * The write's fate is unprovable — money MAY have moved. The row is
     * parked for the reconciler; it is never guessed and never auto-expired.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnknown(UUID id, String reason) {
        transition(id, LedgerStatus.UNKNOWN, reason, tx -> { });
    }

    /**
     * Reconciler bookkeeping after a poll that did NOT reach a terminal
     * state: bump the attempt counter and set the backoff deadline. Not a
     * status transition, so it bypasses the state machine deliberately.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReconcileAttempt(UUID id, Instant nextReconcileAt) {
        jdbcTemplate.update(RECONCILE_ATTEMPT_SQL,
                Timestamp.from(nextReconcileAt), Timestamp.from(clock.instant()), id);
    }

    private void transition(UUID id, LedgerStatus target, String detail,
                            Consumer<LedgerTransaction> mutator) {
        LedgerTransaction tx = repository.findById(id).orElse(null);
        if (tx == null) {
            // Defensive no-op: the row should exist (we just opened it). If it
            // doesn't, something deleted it out from under us — log loudly but
            // don't throw and mask the upstream outcome from the caller.
            log.error("ledger transition to {} requested for missing transaction id={}", target, id);
            return;
        }
        LedgerStatus from = tx.statusEnum();
        if (from == target) {
            // Idempotent replay (e.g. reconciler and request thread racing to
            // record the same outcome) — nothing to do, nothing to journal.
            log.debug("ledger transition no-op id={} already {}", id, target);
            return;
        }
        Set<LedgerStatus> allowed = LEGAL_TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            // Illegal transition — most critically: terminal rows are immutable.
            // Refuse, journal nothing, and shout. (A FAILED->COMPLETED request
            // means two code paths disagree about an outcome — an operator must
            // look, not a best-effort overwrite.)
            illegalTransitions.increment();
            log.error("ILLEGAL ledger transition refused id={} externalRef={} {} -> {} detail={}",
                    id, tx.getExternalRef(), from, target, detail);
            return;
        }
        Instant now = clock.instant();
        mutator.accept(tx);
        tx.setStatus(target.dbValue());
        tx.setUpdatedAt(now);
        repository.save(tx);
        String correlationId = tx.getCorrelationId() != null
                ? tx.getCorrelationId() : CorrelationContext.current();
        recordEvent(id, from, target, detail, correlationId, now);
        transitionCounter(tx.getType(), target).increment();
        auditTransition(tx, from, target, detail);
        publishIfSettled(tx, target, now);
        log.info("ledger {} -> {} id={} externalRef={} coreTxRef={}",
                from, target, id, tx.getExternalRef(), tx.getCoreTxRef());
    }

    /**
     * Announce a TERMINAL outcome for customer-facing side effects (the
     * transaction SMS). Published here, in the one chokepoint every status
     * change passes through, so a row the reconciler resolves an hour later
     * notifies exactly like one that completed on the request thread — and so
     * a future caller cannot forget to.
     *
     * <p>Consumers run {@code AFTER_COMMIT} of THIS transaction: the row is
     * durably terminal before anyone is told about it. SUBMITTED and UNKNOWN
     * are deliberately silent — the first has no outcome yet, and the second
     * means we cannot prove one, which is precisely when a message would be a
     * lie. A parked row that later reconciles publishes its real outcome then.
     */
    private void publishIfSettled(LedgerTransaction tx, LedgerStatus target, Instant now) {
        if (target != LedgerStatus.COMPLETED && target != LedgerStatus.FAILED) {
            return;
        }
        events.publishEvent(new SettledMovementEvent(
                tx.getId(),
                tx.getCustomerId(),
                tx.typeEnum(),
                target,
                tx.getSourceAccount(),
                tx.getDestinationAccount(),
                tx.getAmountMinor(),
                tx.getCurrency(),
                tx.getNarrative(),
                tx.getExternalRef(),
                tx.getCoreTxRef(),
                tx.getCompletedAt() == null ? now : tx.getCompletedAt()));
    }

    /**
     * Seal a tamper-evident audit row for money-significant transitions.
     * SUBMITTED is bookkeeping (no outcome yet) and carries no audit row;
     * UNKNOWN is recorded as FAILURE so "not cleanly completed" is one
     * filter. AuditService swallows its own failures, so this never breaks
     * the ledger write.
     */
    private void auditTransition(LedgerTransaction tx, LedgerStatus from,
                                 LedgerStatus target, String detail) {
        AuditAction action;
        AuditOutcome outcome;
        switch (target) {
            case COMPLETED -> { action = AuditAction.TXN_COMPLETED; outcome = AuditOutcome.SUCCESS; }
            case FAILED -> { action = AuditAction.TXN_FAILED; outcome = AuditOutcome.FAILURE; }
            case UNKNOWN -> { action = AuditAction.TXN_UNKNOWN; outcome = AuditOutcome.FAILURE; }
            default -> { return; }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", from.name());
        metadata.put("to", target.name());
        metadata.put("type", tx.getType());
        metadata.put("amountMinor", tx.getAmountMinor());
        metadata.put("currency", tx.getCurrency());
        metadata.put("externalRef", tx.getExternalRef());
        if (tx.getCoreTxRef() != null) {
            metadata.put("coreTxRef", tx.getCoreTxRef());
        }
        if (detail != null) {
            metadata.put("detail", truncate(detail, 200));
        }
        auditService.record(action, outcome, tx.getCustomerId(), null, metadata);
    }

    private void recordEvent(UUID transactionId, LedgerStatus from, LedgerStatus to,
                             String detail, String correlationId, Instant occurredAt) {
        jdbcTemplate.update(INSERT_EVENT_SQL,
                transactionId,
                from == null ? null : from.dbValue(),
                to.dbValue(),
                truncate(detail, 500),
                correlationId,
                Timestamp.from(occurredAt));
    }

    private Counter transitionCounter(String type, LedgerStatus to) {
        return Counter.builder("innbucks.ledger.transitions")
                .description("Ledger status transitions, tagged by transaction type and target status")
                .tag("type", type)
                .tag("to", to.name())
                .register(meterRegistry);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
