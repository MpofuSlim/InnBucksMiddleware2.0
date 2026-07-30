package zw.co.innbucks.middleware.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the outcome mapping of the one-and-only money-movement path: which
 * core results/exceptions land in which ledger state, what is rethrown, and
 * that the PENDING row is opened BEFORE the core call.
 */
class LedgeredMovementExecutorTest {

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final LedgeredMovementExecutor executor = new LedgeredMovementExecutor(ledgerService);

    private final UUID rowId = UUID.randomUUID();
    private final LedgerDraft draft = new LedgerDraft(
            UUID.randomUUID(), LedgerTransactionType.TRANSFER,
            "SRC-1", "DST-1", new MinorUnits(1234L, "KES"),
            "test transfer", "ext-ref-1", CoreProvider.FINERACT);

    @BeforeEach
    void setUp() {
        LedgerTransaction row = new LedgerTransaction();
        row.setId(rowId);
        row.setStatus(LedgerStatus.PENDING.dbValue());
        row.setExternalRef(draft.externalRef());
        when(ledgerService.openPending(any())).thenReturn(row);
    }

    @Test
    void pendingRowIsOpenedBeforeTheCoreCall() {
        AtomicBoolean openedFirst = new AtomicBoolean(false);
        executor.execute(draft, ref -> {
            // If openPending hasn't happened by the time the core call runs,
            // the write-ahead invariant is broken.
            verify(ledgerService).openPending(any());
            openedFirst.set(true);
            return new TransactionResult(ref, TransactionState.COMPLETED);
        });
        assertThat(openedFirst).isTrue();
    }

    @Test
    void completedResultMarksCompleted() {
        LedgerOutcome outcome = executor.execute(draft,
                ref -> new TransactionResult(new TxRef("CORE-9"), TransactionState.COMPLETED));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.COMPLETED);
        assertThat(outcome.coreTxRef()).isEqualTo("CORE-9");
        verify(ledgerService).markCompleted(eq(rowId), eq("CORE-9"), anyString());
    }

    @Test
    void failedResultMarksFailed() {
        LedgerOutcome outcome = executor.execute(draft,
                ref -> new TransactionResult(ref, TransactionState.FAILED));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.FAILED);
        verify(ledgerService).markFailed(eq(rowId), eq("core_reported_failed"), anyString());
    }

    @Test
    void pendingResultMarksSubmitted() {
        LedgerOutcome outcome = executor.execute(draft,
                ref -> new TransactionResult(ref, TransactionState.PENDING));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.SUBMITTED);
        verify(ledgerService).markSubmitted(eq(rowId), eq(draft.externalRef()));
    }

    @Test
    void unknownResultParksTheRowAndReturns() {
        LedgerOutcome outcome = executor.execute(draft,
                ref -> new TransactionResult(ref, TransactionState.UNKNOWN));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.UNKNOWN);
        verify(ledgerService).markUnknown(eq(rowId), anyString());
    }

    @Test
    void unknownOutcomeExceptionParksAndReturnsWithoutThrowing() {
        // The canonical "money may have moved" case: handled, not an error.
        LedgerOutcome outcome = executor.execute(draft, ref -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, ref,
                    "read timeout mid-flight", null);
        });

        assertThat(outcome.status()).isEqualTo(LedgerStatus.UNKNOWN);
        verify(ledgerService).markUnknown(eq(rowId), anyString());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }

    @Test
    void clientRejectionMarksFailedAndRethrows() {
        assertThatThrownBy(() -> executor.execute(draft, ref -> {
            throw new CoreClientException(CoreProvider.FINERACT, "insufficient balance", null);
        })).isInstanceOf(CoreClientException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("core_rejected"), anyString());
    }

    @Test
    void transientFailureMarksFailedAndRethrows() {
        // CoreTransientException = provably never sent — FAILED is safe.
        assertThatThrownBy(() -> executor.execute(draft, ref -> {
            throw new CoreTransientException(CoreProvider.FINERACT, "connect refused", null);
        })).isInstanceOf(CoreTransientException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("core_unreachable"), anyString());
    }

    @Test
    void unclassifiedExceptionParksConservativelyAndRethrows() {
        // A bug/contract violation could have fired AFTER the write reached
        // the core — FAILED would be a guess; the only safe record is parked.
        assertThatThrownBy(() -> executor.execute(draft, ref -> {
            throw new IllegalStateException("adapter bug");
        })).isInstanceOf(IllegalStateException.class);

        verify(ledgerService).markUnknown(eq(rowId), anyString());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }
}
