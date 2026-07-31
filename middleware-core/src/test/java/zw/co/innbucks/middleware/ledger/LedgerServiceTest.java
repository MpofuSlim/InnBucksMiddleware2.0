package zw.co.innbucks.middleware.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for the transition chokepoint: the legal state machine,
 * terminal immutability, idempotent no-ops, and the journal + audit wiring.
 * No Spring context, no Docker.
 */
class LedgerServiceTest {

    private final LedgerTransactionRepository repository = mock(LedgerTransactionRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-30T10:00:00Z"), ZoneOffset.UTC);

    private LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(repository, jdbcTemplate, auditService, meterRegistry, events, clock);
    }

    private LedgerTransaction row(UUID id, LedgerStatus status) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.setId(id);
        tx.setCustomerId(UUID.randomUUID());
        tx.setType(LedgerTransactionType.TRANSFER.name());
        tx.setSourceAccount("SRC-1");
        tx.setDestinationAccount("DST-1");
        tx.setAmountMinor(1234L);
        tx.setCurrency("KES");
        tx.setExternalRef("ref-" + id);
        tx.setCoreProvider("FINERACT");
        tx.setStatus(status.dbValue());
        tx.setCreatedAt(clock.instant());
        tx.setUpdatedAt(clock.instant());
        return tx;
    }

    @Test
    void legalTransitionSavesJournalsAndAudits() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.PENDING)));

        service.markCompleted(id, "CORE-REF-9", "done");

        verify(repository).save(Mockito.argThat(tx ->
                tx.statusEnum() == LedgerStatus.COMPLETED
                        && "CORE-REF-9".equals(tx.getCoreTxRef())
                        && tx.getCompletedAt() != null));
        // Journal row written in the same call.
        verify(jdbcTemplate).update(anyString(),
                eq(id), eq("PENDING"), eq("COMPLETED"), any(), any(), any());
        verify(auditService).record(eq(AuditAction.TXN_COMPLETED), eq(AuditOutcome.SUCCESS),
                any(UUID.class), Mockito.isNull(), any());
    }

    @Test
    void unknownIsResolvableByReconciler() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.UNKNOWN)));

        service.markCompleted(id, null, "reconciled");

        verify(repository).save(Mockito.argThat(tx -> tx.statusEnum() == LedgerStatus.COMPLETED));
    }

    @Test
    void terminalRowsAreImmutable() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.COMPLETED)));

        service.markFailed(id, "late_failure", "should be refused");

        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
        assertThat(meterRegistry.counter("innbucks.ledger.illegal_transitions").count())
                .isEqualTo(1.0);
    }

    @Test
    void failedRowsAreImmutableToo() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.FAILED)));

        service.markCompleted(id, "CORE-REF", "two code paths disagree");

        verify(repository, never()).save(any());
        assertThat(meterRegistry.counter("innbucks.ledger.illegal_transitions").count())
                .isEqualTo(1.0);
    }

    @Test
    void sameStateIsAnIdempotentNoOp() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.UNKNOWN)));

        service.markUnknown(id, "again");

        verify(repository, never()).save(any());
        verify(jdbcTemplate, never()).update(anyString(),
                eq(id), any(), any(), any(), any(), any());
        assertThat(meterRegistry.counter("innbucks.ledger.illegal_transitions").count())
                .isZero();
    }

    @Test
    void missingRowIsALoudNoOpNeverAThrow() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        // Throwing here would mask the upstream outcome from the caller.
        service.markCompleted(id, "CORE-REF", "row vanished");

        verify(repository, never()).save(any());
    }

    @Test
    void markFailedRecordsFailureDetail() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.PENDING)));

        service.markFailed(id, "core_rejected", "Insufficient funds");

        verify(repository).save(Mockito.argThat(tx ->
                tx.statusEnum() == LedgerStatus.FAILED
                        && "core_rejected".equals(tx.getFailureCode())
                        && "Insufficient funds".equals(tx.getFailureMessage())
                        && tx.getCompletedAt() != null));
        verify(auditService).record(eq(AuditAction.TXN_FAILED), eq(AuditOutcome.FAILURE),
                any(UUID.class), Mockito.isNull(), any());
    }

    @Test
    void submittedIsBookkeepingWithoutAudit() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(row(id, LedgerStatus.PENDING)));

        service.markSubmitted(id, "CORE-REF-1");

        verify(repository).save(Mockito.argThat(tx -> tx.statusEnum() == LedgerStatus.SUBMITTED));
        // No money outcome yet — no tamper-evident audit row.
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
}
