package zw.co.innbucks.middleware.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
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
import static org.mockito.ArgumentMatchers.same;
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
        when(ledgerService.openPending(any(), any())).thenReturn(row);
    }

    @Test
    void pendingRowIsOpenedBeforeTheCoreCall() {
        AtomicBoolean openedFirst = new AtomicBoolean(false);
        executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            // If openPending hasn't happened by the time the core call runs,
            // the write-ahead invariant is broken.
            verify(ledgerService).openPending(any(), any());
            openedFirst.set(true);
            return new TransactionResult(ref, TransactionState.COMPLETED);
        });
        assertThat(openedFirst).isTrue();
    }

    @Test
    void theReservationGuardIsHandedToOpenPendingUnchanged() {
        PendingReservationGuard guard = (d, openedAt) -> { };

        executor.execute(draft, guard, ref -> new TransactionResult(ref, TransactionState.COMPLETED));

        verify(ledgerService).openPending(same(draft), same(guard));
    }

    /**
     * The per-tier limits refuse by throwing from inside openPending: that must
     * reach the caller as is, before any core call and without a ledger mark
     * (there is no row to mark).
     */
    @Test
    void aGuardRefusalMakesNoCoreCallAndMarksNothing() {
        RuntimeException refusal = new IllegalStateException("limit refused");
        when(ledgerService.openPending(any(), any())).thenThrow(refusal);
        AtomicBoolean coreCalled = new AtomicBoolean(false);

        assertThatThrownBy(() -> executor.execute(draft, (d, openedAt) -> { }, ref -> {
            coreCalled.set(true);
            return new TransactionResult(ref, TransactionState.COMPLETED);
        })).isSameAs(refusal);

        assertThat(coreCalled).isFalse();
        verify(ledgerService, never()).markFailed(any(), any(), any());
        verify(ledgerService, never()).markUnknown(any(), any());
        verify(ledgerService, never()).markCompleted(any(), any(), any());
    }

    @Test
    void completedResultMarksCompleted() {
        LedgerOutcome outcome = executor.execute(draft, PendingReservationGuard.NONE,
                ref -> new TransactionResult(new TxRef("CORE-9"), TransactionState.COMPLETED));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.COMPLETED);
        assertThat(outcome.coreTxRef()).isEqualTo("CORE-9");
        verify(ledgerService).markCompleted(eq(rowId), eq("CORE-9"), anyString());
    }

    @Test
    void failedResultMarksFailed() {
        LedgerOutcome outcome = executor.execute(draft, PendingReservationGuard.NONE,
                ref -> new TransactionResult(ref, TransactionState.FAILED));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.FAILED);
        verify(ledgerService).markFailed(eq(rowId), eq("core_reported_failed"), anyString());
    }

    @Test
    void pendingResultMarksSubmitted() {
        LedgerOutcome outcome = executor.execute(draft, PendingReservationGuard.NONE,
                ref -> new TransactionResult(ref, TransactionState.PENDING));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.SUBMITTED);
        verify(ledgerService).markSubmitted(eq(rowId), eq(draft.externalRef()));
    }

    @Test
    void unknownResultParksTheRowAndReturns() {
        LedgerOutcome outcome = executor.execute(draft, PendingReservationGuard.NONE,
                ref -> new TransactionResult(ref, TransactionState.UNKNOWN));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.UNKNOWN);
        verify(ledgerService).markUnknown(eq(rowId), anyString());
    }

    @Test
    void unknownOutcomeExceptionParksAndReturnsWithoutThrowing() {
        // The canonical "money may have moved" case: handled, not an error.
        LedgerOutcome outcome = executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, ref,
                    "read timeout mid-flight", null);
        });

        assertThat(outcome.status()).isEqualTo(LedgerStatus.UNKNOWN);
        verify(ledgerService).markUnknown(eq(rowId), anyString());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }

    @Test
    void clientRejectionMarksFailedAndRethrows() {
        assertThatThrownBy(() -> executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new CoreClientException(CoreProvider.FINERACT, "insufficient balance", null);
        })).isInstanceOf(CoreClientException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("core_rejected"), anyString());
    }

    @Test
    void aRestrictedAccountIsStillAProvenFailureCodedForOps() {
        // A CoreClientException subtype: FAILED (nothing applied), rethrown for
        // the 403, and coded so a same-key retry replays the fixed copy.
        assertThatThrownBy(() -> executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new CoreAccountRestrictedException(CoreProvider.FINERACT,
                    "Fineract refused to debit a restricted account", null);
        })).isInstanceOf(CoreAccountRestrictedException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("account_restricted"), anyString());
        verify(ledgerService, never()).markUnknown(any(), any());
    }

    @Test
    void authorityRefusalMarksFailedAndRethrowsNeverParks() {
        // A permission the core refuses our AppUser (e.g. a deposit on a cell
        // provisioned without customer deposits) is refused before the command
        // runs, so FAILED is provable. Parking it UNKNOWN would page an
        // operator to reconcile a movement that never existed.
        assertThatThrownBy(() -> executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new CoreAuthException(CoreProvider.FINERACT,
                    "Fineract refused the middleware AppUser a permission: error.msg.not.authorized: "
                            + "User has no authority to: DEPOSIT_SAVINGSACCOUNT", null);
        })).isInstanceOf(CoreAuthException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("core_auth_failed"), anyString());
        verify(ledgerService, never()).markUnknown(any(), any());
    }

    @Test
    void onlyOurOwnFailuresCountAsOperational() {
        // These rows hold operator detail that a replay must not show the customer.
        assertThat(LedgeredMovementExecutor.isOperationalFailure("core_auth_failed")).isTrue();
        assertThat(LedgeredMovementExecutor.isOperationalFailure("core_unreachable")).isTrue();
        assertThat(LedgeredMovementExecutor.isOperationalFailure("core_server_error")).isTrue();
        // A refusal of the request itself keeps its wording ("Insufficient account balance.").
        assertThat(LedgeredMovementExecutor.isOperationalFailure("core_rejected")).isFalse();
        assertThat(LedgeredMovementExecutor.isOperationalFailure("core_reported_failed")).isFalse();
        assertThat(LedgeredMovementExecutor.isOperationalFailure("reconciled_failed")).isFalse();
        assertThat(LedgeredMovementExecutor.isOperationalFailure(null)).isFalse();
    }

    @Test
    void transientFailureMarksFailedAndRethrows() {
        // CoreTransientException = provably never sent — FAILED is safe.
        assertThatThrownBy(() -> executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new CoreTransientException(CoreProvider.FINERACT, "connect refused", null);
        })).isInstanceOf(CoreTransientException.class);

        verify(ledgerService).markFailed(eq(rowId), eq("core_unreachable"), anyString());
    }

    @Test
    void unclassifiedExceptionParksConservativelyAndRethrows() {
        // A bug/contract violation could have fired AFTER the write reached
        // the core — FAILED would be a guess; the only safe record is parked.
        assertThatThrownBy(() -> executor.execute(draft, PendingReservationGuard.NONE, ref -> {
            throw new IllegalStateException("adapter bug");
        })).isInstanceOf(IllegalStateException.class);

        verify(ledgerService).markUnknown(eq(rowId), anyString());
        verify(ledgerService, never()).markFailed(any(), any(), any());
    }
}
