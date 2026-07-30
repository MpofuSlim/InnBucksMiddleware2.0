package zw.co.innbucks.middleware.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/**
 * Lowest-precedence safety net: every exception that isn't claimed by a more
 * specific {@code @RestControllerAdvice} (core-banking, Auth, OTP)
 * lands here and comes out as a customer-friendly ProblemDetail with an
 * {@code errorCode} — never a Spring-default {@code "Internal Server Error"}
 * with no body shape, and never an upstream core's raw text.
 *
 * <p>The framework exceptions (bad JSON, missing header, wrong content type,
 * unknown route, wrong method) get explicit handlers so the customer sees
 * the right status + friendly copy instead of Spring's terse defaults. The
 * final {@code Throwable} catch-all covers truly unexpected bugs — those
 * are logged at ERROR with the stack trace for on-call, but the customer
 * only sees "Something went wrong on our side".
 *
 * <p>{@code application.yaml} already sets {@code server.error.include-message}
 * and {@code include-stacktrace} to {@code never}, which is a defence-in-depth
 * for any leak path that bypasses Spring MVC entirely (servlet-container 5xx,
 * filter-level failure). This advice owns the MVC-level leak surface.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        log.info("Validation failure: {} field error(s)", ex.getErrorCount());
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid request",
                "Some details in your request weren't valid. Please check and try again.",
                "validation_failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        // Body wasn't valid JSON / didn't match the expected shape.
        log.info("Unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid request",
                "We couldn't read your request. Please check the format and try again.",
                "malformed_request");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> missingParam(MissingServletRequestParameterException ex) {
        log.info("Missing request parameter: {}", ex.getParameterName());
        return problem(HttpStatus.BAD_REQUEST,
                "Missing information",
                "A required piece of information is missing from your request. Please try again.",
                "missing_parameter");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> missingHeader(MissingRequestHeaderException ex) {
        log.info("Missing required header: {}", ex.getHeaderName());
        return problem(HttpStatus.BAD_REQUEST,
                "Missing information",
                "A required piece of information is missing from your request. Please try again.",
                "missing_header");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> wrongContentType(HttpMediaTypeNotSupportedException ex) {
        log.info("Unsupported media type: {}", ex.getContentType());
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported content type",
                "We couldn't process this request format. Please use JSON.",
                "unsupported_media_type");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> wrongMethod(HttpRequestMethodNotSupportedException ex) {
        log.info("Method not allowed: {}", ex.getMethod());
        return problem(HttpStatus.METHOD_NOT_ALLOWED,
                "Action not allowed",
                "That action isn't supported on this endpoint.",
                "method_not_allowed");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ProblemDetail> notFound(Exception ex) {
        log.info("No handler for request: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND,
                "Not found",
                "We couldn't find what you were looking for.",
                "not_found");
    }

    /**
     * Method-security ({@code @PreAuthorize}) denial — e.g. the access token
     * lacks the required scope. Spring throws {@code AuthorizationDeniedException}
     * (an {@link AccessDeniedException}) from the controller invocation, so it
     * lands in this advice rather than at the security filter's 403 handler.
     * Without this explicit mapping the {@code Throwable} catch-all below would
     * dress an authorization failure up as a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex) {
        log.info("Access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN,
                "Forbidden",
                "Your access token doesn't grant permission for this action.",
                "insufficient_scope");
    }

    @ExceptionHandler(zw.co.innbucks.middleware.idempotency.IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> idempotencyConflict(
            zw.co.innbucks.middleware.idempotency.IdempotencyConflictException ex) {
        log.info("Idempotency conflict: {}", ex.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency key conflict",
                "This idempotency key was already used with a different request. Use a new key.",
                "idempotency_conflict");
    }

    @ExceptionHandler(zw.co.innbucks.middleware.idempotency.IdempotencyInProgressException.class)
    public ResponseEntity<ProblemDetail> idempotencyInProgress(
            zw.co.innbucks.middleware.idempotency.IdempotencyInProgressException ex) {
        log.info("Idempotency in progress: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT,
                "Request already in progress",
                "An identical request is still being processed. Please retry shortly with the same key.",
                "idempotency_in_progress");
    }

    /**
     * Genuine "we didn't expect this" — internal bug, infra glitch, or an
     * exception class no specific advice handles. Log at ERROR with the full
     * stack so on-call can investigate, surface a friendly generic message to
     * the customer.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> unexpected(Throwable ex) {
        log.error("Unhandled exception: {}", ex.getClass().getName(), ex);
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Something went wrong on our side");
        body.setDetail("We hit an unexpected problem. Please try again in a moment. " +
                "If the issue continues, please contact support.");
        body.setProperty("errorCode", "internal_error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title,
                                                   String detail, String errorCode) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(PROBLEM_TYPE);
        body.setTitle(title);
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
