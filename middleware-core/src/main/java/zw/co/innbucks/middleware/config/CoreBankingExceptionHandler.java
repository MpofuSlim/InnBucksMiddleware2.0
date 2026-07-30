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
 * <p>Only {@link CoreClientException} carries upstream wording to the caller
 * (it is the "your request was refused" case — insufficient balance, unknown
 * account). Auth/server/transient failures are OUR ops problem; the customer
 * gets a generic retryable message and the detail goes to the logs.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class CoreBankingExceptionHandler {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

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
        // Our service credentials — an ops incident, never customer detail.
        log.error("Core banking credentials rejected: {}", ex.getMessage());
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
