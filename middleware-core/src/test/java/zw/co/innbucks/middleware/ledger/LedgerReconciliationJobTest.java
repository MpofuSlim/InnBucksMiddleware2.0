package zw.co.innbucks.middleware.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the reconciler's money rules: stale PENDING is PARKED (never failed),
 * only POSITIVE core outcomes resolve a row, everything else backs off, one
 * row's failure never stalls the sweep, and no adapter bean means rows wait.
 */
class LedgerReconciliationJobTest {

    private final Instant now = Instant.parse("2026-07-30T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final LedgerTransactionRepository repository = mock(LedgerTransactionRepository.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CoreBankingPort> portProvider = mock(ObjectProvider.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final LedgerProperties properties = new LedgerProperties(
            Duration.ofMinutes(5), 100,
            Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(1));

    private LedgerReconciliationJob job;

    @BeforeEach
    void setUp() {
        when(portProvider.getIfAvailable()).thenReturn(port);
        when(repository.findStalePending(any(), anyInt())).thenReturn(List.of());
        when(repository.findDueForReconciliation(any(), anyInt())).thenReturn(List.of());
        when(repository.countParkedOlderThan(any())).thenReturn(0L);
        job = new LedgerReconciliationJob(repository, ledgerService, portProvider,
                properties, meterRegistry, clock);
    }

    /** Matches the lookup the job builds from a row (by external ref). */
    private static TransactionLookup lookupOf(LedgerTransaction tx) {
        return org.mockito.ArgumentMatchers.argThat(l ->
                l != null && l.externalRef().reference().equals(tx.getExternalRef()));
    }

    private LedgerTransaction row(LedgerStatus status, int attempts) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.setId(UUID.randomUUID());
        tx.setStatus(status.dbValue());
        tx.setExternalRef("ref-" + tx.getId());
        tx.setType(LedgerTransactionType.TRANSFER.name());
        tx.setAmountMinor(500L);
        tx.setCreatedAt(now.minus(Duration.ofMinutes(10)));
        tx.setReconcileAttempts(attempts);
        return tx;
    }

    @Test
    void stalePendingIsParkedNeverFailed() {
        LedgerTransaction stale = row(LedgerStatus.PENDING, 0);
        when(repository.findStalePending(eq(now.minus(Duration.ofMinutes(5))), anyInt()))
                .thenReturn(List.of(stale));

        job.sweep();

        // The core write MAY have been sent — parking is the only safe record.
        verify(ledgerService).markUnknown(eq(stale.getId()), anyString());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }

    @Test
    void coreCompletedResolvesTheRow() {
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 0);
        when(repository.findDueForReconciliation(eq(now), anyInt())).thenReturn(List.of(parked));
        when(port.getTransaction(lookupOf(parked)))
                .thenReturn(new TransactionResult(new TxRef("CORE-77"), TransactionState.COMPLETED));

        job.sweep();

        verify(ledgerService).markCompleted(eq(parked.getId()), eq("CORE-77"), anyString());
        assertThat(meterRegistry.counter("innbucks.ledger.reconcile", "outcome", "resolved_completed")
                .count()).isEqualTo(1.0);
    }

    @Test
    void coreFailedResolvesTheRowAsFailed() {
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 0);
        when(repository.findDueForReconciliation(eq(now), anyInt())).thenReturn(List.of(parked));
        when(port.getTransaction(any())).thenReturn(
                new TransactionResult(new TxRef(parked.getExternalRef()), TransactionState.FAILED));

        job.sweep();

        verify(ledgerService).markFailed(eq(parked.getId()), eq("reconciled_failed"), anyString());
    }

    @Test
    void corePendingPromotesToSubmittedAndBacksOff() {
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 0);
        when(repository.findDueForReconciliation(eq(now), anyInt())).thenReturn(List.of(parked));
        when(port.getTransaction(any())).thenReturn(
                new TransactionResult(new TxRef(parked.getExternalRef()), TransactionState.PENDING));

        job.sweep();

        verify(ledgerService).markSubmitted(eq(parked.getId()), eq(parked.getExternalRef()));
        // attempts=0 -> next poll after base (1 min).
        verify(ledgerService).recordReconcileAttempt(parked.getId(), now.plus(Duration.ofMinutes(1)));
    }

    @Test
    void coreUnknownLeavesTheRowParkedWithBackoff() {
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 2);
        when(repository.findDueForReconciliation(eq(now), anyInt())).thenReturn(List.of(parked));
        when(port.getTransaction(any())).thenReturn(
                new TransactionResult(new TxRef(parked.getExternalRef()), TransactionState.UNKNOWN));

        job.sweep();

        verify(ledgerService, never()).markCompleted(any(), any(), any());
        verify(ledgerService, never()).markFailed(any(), any(), any());
        // attempts=2 -> base * 2^2 = 4 min.
        verify(ledgerService).recordReconcileAttempt(parked.getId(), now.plus(Duration.ofMinutes(4)));
    }

    @Test
    void backoffIsCappedForHighAttemptCounts() {
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 15);
        when(repository.findDueForReconciliation(eq(now), anyInt())).thenReturn(List.of(parked));
        when(port.getTransaction(any())).thenReturn(
                new TransactionResult(new TxRef(parked.getExternalRef()), TransactionState.UNKNOWN));

        job.sweep();

        // 1 min * 2^15 would be ~22 days; the cap (30 min) wins.
        verify(ledgerService).recordReconcileAttempt(parked.getId(), now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void queryFailureIsIsolatedPerRow() {
        LedgerTransaction failing = row(LedgerStatus.UNKNOWN, 0);
        LedgerTransaction resolvable = row(LedgerStatus.UNKNOWN, 0);
        when(repository.findDueForReconciliation(eq(now), anyInt()))
                .thenReturn(List.of(failing, resolvable));
        when(port.getTransaction(lookupOf(failing)))
                .thenThrow(new IllegalStateException("core query blew up"));
        when(port.getTransaction(lookupOf(resolvable)))
                .thenReturn(new TransactionResult(new TxRef("CORE-88"), TransactionState.COMPLETED));

        job.sweep();

        // The second row still resolves; the first backs off and stays parked.
        verify(ledgerService).markCompleted(eq(resolvable.getId()), eq("CORE-88"), anyString());
        verify(ledgerService).recordReconcileAttempt(eq(failing.getId()), any());
        assertThat(meterRegistry.counter("innbucks.ledger.reconcile", "outcome", "error")
                .count()).isEqualTo(1.0);
    }

    @Test
    void withoutAnAdapterRowsStayParkedAndStaleSweepStillRuns() {
        when(portProvider.getIfAvailable()).thenReturn(null);
        LedgerTransaction stale = row(LedgerStatus.PENDING, 0);
        LedgerTransaction parked = row(LedgerStatus.UNKNOWN, 0);
        when(repository.findStalePending(any(), anyInt())).thenReturn(List.of(stale));
        when(repository.findDueForReconciliation(any(), anyInt())).thenReturn(List.of(parked));

        job.sweep();

        verify(ledgerService).markUnknown(eq(stale.getId()), anyString());
        verify(ledgerService, never()).markCompleted(any(), any(), any());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }

    @Test
    void overdueParkedRowsTripTheOperatorAlarm() {
        when(repository.countParkedOlderThan(eq(now.minus(Duration.ofHours(1))))).thenReturn(3L);

        job.sweep();

        assertThat(meterRegistry.counter("innbucks.ledger.parked_overdue").count()).isEqualTo(1.0);
    }
}
