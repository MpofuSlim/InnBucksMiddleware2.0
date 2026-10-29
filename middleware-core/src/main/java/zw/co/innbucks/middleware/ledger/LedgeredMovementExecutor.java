package zw.co.innbucks.middleware.ledger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.util.function.Function;

/**
 * The one way to run a money movement against the core: write-ahead PENDING
 * row, then the call, then record what actually happened. Future endpoints
 * (deposit/withdraw/transfer) call THIS, never the port directly, so the
 * ledger can never miss a movement.
 *
 * <p>Outcome mapping — the whole point of the taxonomy:
 * <ul>
 *   <li>Result state COMPLETED / FAILED → terminal marks.</li>
 *   <li>Result state PENDING → SUBMITTED (core accepted; reconciler polls).</li>
 *   <li>Result state UNKNOWN or {@link CoreUnknownOutcomeException} →
 *       UNKNOWN, returned (not thrown): the caller renders PROCESSING.</li>
 *   <li>{@link CoreClientException} / {@link CoreAuthException} /
 *       {@link CoreTransientException} / {@link CoreServerException} →
 *       FAILED + rethrown. By the port contract these all mean the write
 *       provably did NOT apply (rejected, creds refused, never sent, or 5xx
 *       before send), so FAILED is safe.</li>
 *   <li>Any OTHER exception → UNKNOWN + rethrown. An unclassified failure
 *       could have happened after the write reached the core; parking is
 *       the only safe record. The rethrow surfaces the bug; the parked row
 *       keeps the money reconcilable.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LedgeredMovementExecutor {

    /*
     * failure_code values for the infrastructure failures below. Their
     * failure_message is the adapter's ops detail (it can quote the core's
     * reply), which the first answer never showed the customer — a same-key
     * replay must not either (MovementService).
     */
    public static final String CORE_AUTH_FAILED = "core_auth_failed";
    public static final String CORE_UNREACHABLE = "core_unreachable";
    public static final String CORE_SERVER_ERROR = "core_server_error";

    private final LedgerService ledgerService;

    /**
     * Opens the write-ahead PENDING row — committed (REQUIRES_NEW) before the
     * core call goes out — but only if {@code reservationGuard} lets it,
     * checked inside the same transaction as the INSERT (see
     * {@link LedgerService#openPending}). A refusal propagates from HERE,
     * before the try block below: no row, no core call, nothing to reconcile.
     *
     * <p>There is deliberately no overload without a guard: every caller names
     * one, so a withdrawal or transfer opened with
     * {@link PendingReservationGuard#NONE} — i.e. past the per-tier payment
     * limits — is visible in review rather than a default.
     */
    public LedgerOutcome execute(LedgerDraft draft,
                                 PendingReservationGuard reservationGuard,
                                 Function<TxRef, TransactionResult> coreCall) {
        return callCore(draft, ledgerService.openPending(draft, reservationGuard), coreCall);
    }

    private LedgerOutcome callCore(LedgerDraft draft, LedgerTransaction row,
                                   Function<TxRef, TransactionResult> coreCall) {
        TxRef ref = new TxRef(draft.externalRef());

        TransactionResult result;
        try {
            result = coreCall.apply(ref);
        } catch (CoreUnknownOutcomeException ex) {
            ledgerService.markUnknown(row.getId(),
                    "Core outcome unprovable: " + safeMessage(ex));
            return new LedgerOutcome(row.getId(), LedgerStatus.UNKNOWN, null);
        } catch (CoreClientException | CoreAuthException | CoreTransientException | CoreServerException ex) {
            // Port contract: these categories guarantee the write did not apply.
            ledgerService.markFailed(row.getId(), failureCode(ex), safeMessage(ex));
            throw ex;
        } catch (RuntimeException ex) {
            // Contract violation / bug: we cannot prove the write didn't land.
            log.error("Unclassified exception from core call — parking ledger row id={} externalRef={}",
                    row.getId(), draft.externalRef(), ex);
            ledgerService.markUnknown(row.getId(),
                    "Unclassified exception from core call: " + safeMessage(ex));
            throw ex;
        }

        return switch (result.state()) {
            case COMPLETED -> {
                ledgerService.markCompleted(row.getId(), result.ref().reference(),
                        "Core confirmed the movement applied");
                yield new LedgerOutcome(row.getId(), LedgerStatus.COMPLETED, result.ref().reference());
            }
            case FAILED -> {
                ledgerService.markFailed(row.getId(), "core_reported_failed",
                        "Core reported the movement did not apply");
                yield new LedgerOutcome(row.getId(), LedgerStatus.FAILED, result.ref().reference());
            }
            case PENDING -> {
                ledgerService.markSubmitted(row.getId(), result.ref().reference());
                yield new LedgerOutcome(row.getId(), LedgerStatus.SUBMITTED, result.ref().reference());
            }
            case UNKNOWN -> {
                ledgerService.markUnknown(row.getId(), "Core returned UNKNOWN state");
                yield new LedgerOutcome(row.getId(), LedgerStatus.UNKNOWN, result.ref().reference());
            }
        };
    }

    private static String failureCode(RuntimeException ex) {
        // Before its parent: a restriction is worth telling apart in the ledger
        // (ops: "which failures were blocked accounts"), and the same-key
        // replay answers from this code.
        if (ex instanceof CoreAccountRestrictedException) return "account_restricted";
        if (ex instanceof CoreClientException) return "core_rejected";
        if (ex instanceof CoreAuthException) return CORE_AUTH_FAILED;
        if (ex instanceof CoreTransientException) return CORE_UNREACHABLE;
        return CORE_SERVER_ERROR;
    }

    private static String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        return msg == null ? ex.getClass().getSimpleName() : msg;
    }
}
