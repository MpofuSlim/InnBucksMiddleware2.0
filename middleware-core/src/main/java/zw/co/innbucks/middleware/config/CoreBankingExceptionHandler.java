package zw.co.innbucks.middleware.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.co.innbucks.middleware.corebanking.exception.CoreAccountRestrictedException;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;

import java.net.URI;

/**
 * Maps the core-neutral exception taxonomy to customer-facing ProblemDetails.
 * Precedence sits above the global catch-all so a core failure never comes
 * out dressed as a bare 500.
 *
 * <p>Only {@link CoreClientException} carries its message to the caller (it is
 * the "your request was refused" case — insufficient balance, unknown
 * account), and this handler repeats that message as it is: it cannot tell a
 * safe sentence from a leaky one. So adapters own the wording. On a deposit or
 * withdrawal it may be the core's own (it concerns the caller's own account);
 * on a TRANSFER it is never the core's — fixed copy, because the core's
 * sentences there can name the recipient's account and state — save the one
 * refusal the adapter makes before asking the core (an amount not in the
 * cell's currency), which is our own sentence naming only the two currencies.
 * Auth/server/transient failures
 * are OUR ops problem; the customer gets a generic retryable message and the
 * detail goes to the logs.
 *
 * <p>{@link CoreAccountRestrictedException} — the bank has blocked the
 * account the movement debits — is the one client refusal that carries NO
 * upstream wording: 403 {@code account_restricted} with fixed copy. Why an
 * account is restricted (fraud hold, compliance, a dispute) is the bank's to
 * tell, and the app needs only to stop retrying and point at support.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class CoreBankingExceptionHandler {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    public static final String ACCOUNT_RESTRICTED = "account_restricted";

    /**
     * The whole customer-facing story of a restriction. Also what a same-key
     * retry of the refused movement replays (MovementService), so the two
     * answers can never say different things.
     */
    public static final String ACCOUNT_RESTRICTED_DETAIL =
            "Payments from this account are paused. Please contact support.";

    @ExceptionHandler(CoreAccountRestrictedException.class)
    public ResponseEntity<ProblemDetail> restricted(CoreAccountRestrictedException ex) {
        // Most specific handler wins over rejected() below (Spring matches by
        // exception depth). The upstream detail stays in the log.
        log.info("Core refused a debit from a restricted account: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN,
                "Account restricted",
                ACCOUNT_RESTRICTED_DETAIL,
                ACCOUNT_RESTRICTED);
    }

    @ExceptionHandler(CoreClientException.class)
    public ResponseEntity<ProblemDetail> rejected(CoreClientException ex) {
        log.info("Core rejected the request: {}", ex.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                "Request refused",
                trim(ex.getMessage()),
                "core_rejected");
    }

    @ExceptionHandler(CoreAuthException.class)
    public ResponseEntity<ProblemDetail> upstreamAuth(CoreAuthException ex) {
        // Our service credentials, or a permission they lack (e.g. a cell
        // provisioned without customer deposits while the deposit switch is
        // on) — an ops incident, never customer detail.
        log.error("Core banking refused our service credentials or a permission they need: {}",
                ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY,
                "Service temporarily unavailable",
                "We couldn't reach the banking system. Please try again shortly.",
                "core_banking_unavailable");
    }

    @ExceptionHandler({CoreServerException.class, CoreTransientException.class})
    public ResponseEntity<ProblemDetail> upstreamDown(RuntimeException ex) {
        log.warn("Core banking unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable",
                "We couldn't reach the banking system. Please try again shortly.",
                "core_banking_unavailable");
    }

    /**
     * Should not escape money paths (the ledger executor converts it to a
     * PROCESSING outcome) — this covers reads and future call sites so an
     * ambiguous outcome is never presented as a clean failure.
     */
    @ExceptionHandler(CoreUnknownOutcomeException.class)
    public ResponseEntity<ProblemDetail> unknownOutcome(CoreUnknownOutcomeException ex) {
        log.error("Core outcome unknown surfaced to the web layer (txRef={}): {}",
                ex.txRef() == null ? null : ex.txRef().reference(), ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Still processing",
                "The banking system did not confirm the outcome yet. Do not retry — "
                        + "check your account shortly.",
                "core_outcome_unknown");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title,
                                                  String detail, String errorCode) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(PROBLEM_TYPE);
        body.setTitle(title);
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) {
            return "The banking system refused this request.";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
