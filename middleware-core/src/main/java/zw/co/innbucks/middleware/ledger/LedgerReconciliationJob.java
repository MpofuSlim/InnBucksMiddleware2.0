package zw.co.innbucks.middleware.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.MovementKind;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The resolver for parked and in-flight ledger rows. Three sweeps per pass
 * (fixed-delay so a slow pass never piles up overlapping invocations):
 *
 * <ol>
 *   <li><b>Stale PENDING → UNKNOWN:</b> a PENDING row past the threshold
 *       means the process died between opening the row and recording the
 *       call's outcome. Unlike a flow whose token was never delivered, the
 *       core write MAY have been sent — so these rows are PARKED for
 *       reconciliation, never auto-failed. (This deliberately differs from
 *       the ticketing code-payment flow, where stale PENDING is provably
 *       undelivered and safe to fail.)</li>
 *   <li><b>Due UNKNOWN / SUBMITTED rows:</b> each is queried upstream by its
 *       {@code external_ref} via {@code CoreBankingPort.getTransaction} with
 *       per-row isolation — one row's failure never stalls the rest.
 *       COMPLETED / FAILED from the core are POSITIVE outcomes and resolve
 *       the row; PENDING moves it to SUBMITTED; UNKNOWN or a query error
 *       leaves it parked with exponential backoff. <b>Nothing here ever
 *       expires or fails a row the core hasn't positively resolved.</b></li>
 *   <li><b>Parked-overdue alarm:</b> UNKNOWN rows older than the alert
 *       threshold bump {@code innbucks.ledger.parked_overdue} every sweep —
 *       a sustained non-zero rate is customers whose money is in limbo, the
 *       loudest page this middleware owns.</li>
 * </ol>
 *
 * <p>The port is resolved lazily via {@link ObjectProvider}: until a core
 * adapter bean lands (next slice), sweeps 1 and 3 still run — only the
 * upstream queries of sweep 2 wait for an adapter.
 */
@Slf4j
@Component
public class LedgerReconciliationJob {

    private final LedgerTransactionRepository repository;
    private final LedgerService ledgerService;
    private final ObjectProvider<CoreBankingPort> corePort;
    private final LedgerProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Counter parkedOverdue;

    public LedgerReconciliationJob(LedgerTransactionRepository repository,
                                   LedgerService ledgerService,
                                   ObjectProvider<CoreBankingPort> corePort,
                                   LedgerProperties properties,
                                   MeterRegistry meterRegistry,
                                   Clock clock) {
        this.repository = repository;
        this.ledgerService = ledgerService;
        this.corePort = corePort;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.parkedOverdue = Counter.builder("innbucks.ledger.parked_overdue")
                .description("Sweeps that found UNKNOWN rows parked past the alert threshold — money in limbo, operator attention required")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${innbucks.ledger.reconcile-interval:PT1M}")
    public void sweep() {
        Instant now = clock.instant();
        parkStalePending(now);
        reconcileDueRows(now);
        alarmParkedOverdue(now);
    }

    private void parkStalePending(Instant now) {
        Instant cutoff = now.minus(properties.stalePendingThreshold());
        List<LedgerTransaction> stale = repository.findStalePending(cutoff, properties.batchSize());
        for (LedgerTransaction tx : stale) {
            long ageSeconds = Duration.between(tx.getCreatedAt(), now).toSeconds();
            log.warn("Stale PENDING ledger row — parking as UNKNOWN id={} externalRef={} type={} amountMinor={} ageSeconds={}",
                    tx.getId(), tx.getExternalRef(), tx.getType(), tx.getAmountMinor(), ageSeconds);
            ledgerService.markUnknown(tx.getId(),
                    "Stale PENDING (" + ageSeconds + "s): process died mid-call; parked for reconciliation");
        }
        if (stale.size() == properties.batchSize()) {
            log.warn("Stale-PENDING sweep hit batch cap ({}); more rows likely behind it — investigate systemic stalling",
                    properties.batchSize());
        }
    }

    private void reconcileDueRows(Instant now) {
        List<LedgerTransaction> due = repository.findDueForReconciliation(now, properties.batchSize());
        if (due.isEmpty()) {
            return;
        }
        CoreBankingPort port = corePort.getIfAvailable();
        if (port == null) {
            log.warn("Reconciliation: {} row(s) due but no CoreBankingPort adapter is wired — rows stay parked",
                    due.size());
            return;
        }
        for (LedgerTransaction tx : due) {
            // Per-row isolation: one row's query failure must not stall the rest.
            try {
                reconcileRow(port, tx, now);
            } catch (RuntimeException ex) {
                reconcileCounter("error").increment();
                log.warn("Reconciliation query failed externalRef={} — leaving row for next pass: {}",
                        tx.getExternalRef(), ex.getMessage());
                backoff(tx, now);
            }
        }
        if (due.size() == properties.batchSize()) {
            log.warn("Reconciliation sweep hit batch cap ({}); more due rows likely behind it", properties.batchSize());
        }
    }

    private void reconcileRow(CoreBankingPort port, LedgerTransaction tx, Instant now) {
        TransactionResult result = port.getTransaction(lookupFor(tx));
        switch (result.state()) {
            case COMPLETED -> {
                ledgerService.markCompleted(tx.getId(), result.ref().reference(),
                        "Reconciler: core confirms the movement applied");
                reconcileCounter("resolved_completed").increment();
            }
            case FAILED -> {
                // POSITIVE outcome from the core (found-failed or definitively
                // never received, per the port contract) — safe to close.
                ledgerService.markFailed(tx.getId(), "reconciled_failed",
                        "Reconciler: core reports the movement did not apply");
                reconcileCounter("resolved_failed").increment();
            }
            case PENDING -> {
                // The write definitely reached the core; stop treating it as
                // ambiguous, keep polling.
                ledgerService.markSubmitted(tx.getId(), result.ref().reference());
                reconcileCounter("still_processing").increment();
                backoff(tx, now);
            }
            case UNKNOWN -> {
                // NEVER guess: the row stays parked and the metric drips.
                reconcileCounter("still_unknown").increment();
                log.warn("Reconciliation: core cannot resolve externalRef={} (attempt {}) — row stays parked",
                        tx.getExternalRef(), tx.getReconcileAttempts() + 1);
                backoff(tx, now);
            }
        }
    }

    private void alarmParkedOverdue(Instant now) {
        Instant cutoff = now.minus(properties.parkedAlertThreshold());
        long overdue = repository.countParkedOlderThan(cutoff);
        if (overdue > 0) {
            parkedOverdue.increment();
            log.error("OPERATOR ATTENTION: {} ledger row(s) parked UNKNOWN for over {} — money in limbo; "
                            + "resolve against the core's records (never guess locally)",
                    overdue, properties.parkedAlertThreshold());
        }
    }

    /** The ledger row is the reconciliation context: kind + accounts tell the adapter where to query. */
    private static TransactionLookup lookupFor(LedgerTransaction tx) {
        return new TransactionLookup(
                new TxRef(tx.getExternalRef()),
                MovementKind.valueOf(tx.getType()),
                tx.getSourceAccount() == null ? null : new AccountRef(tx.getSourceAccount()),
                tx.getDestinationAccount() == null ? null : new AccountRef(tx.getDestinationAccount()));
    }

    private void backoff(LedgerTransaction tx, Instant now) {
        long factor = 1L << Math.min(tx.getReconcileAttempts(), 20);
        Duration delay = properties.reconcileBackoffBase().multipliedBy(factor);
        if (delay.compareTo(properties.reconcileBackoffCap()) > 0) {
            delay = properties.reconcileBackoffCap();
        }
        ledgerService.recordReconcileAttempt(tx.getId(), now.plus(delay));
    }

    private Counter reconcileCounter(String outcome) {
        return Counter.builder("innbucks.ledger.reconcile")
                .description("Reconciliation poll outcomes for parked/in-flight ledger rows")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
