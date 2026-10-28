package zw.co.innbucks.middleware.kyc.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.KycRequestException;
import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException;
import zw.co.innbucks.middleware.kyc.image.KycUploadBusyException;
import zw.co.innbucks.middleware.kyc.nationalid.InvalidNationalIdException;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;

import java.net.URI;

/**
 * The KYC endpoints' refusals, in the house ProblemDetail shape
 * ({@code errorCode} on every body). Scoped to {@link KycController}; shared
 * refusals (429 {@code rate_limited}, 503 {@code kyc_tier_unavailable},
 * idempotency 409/422, validation) keep their existing global handlers.
 *
 * <p>Nothing here echoes what the customer sent: a refusal of an ID number
 * says what SHAPE is expected, never the number.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = KycController.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnKycEnabled
public class KycExceptionHandler {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    @ExceptionHandler(KycRequestException.class)
    public ResponseEntity<ProblemDetail> refused(KycRequestException ex) {
        log.info("KYC request refused: {}", ex.errorCode());
        ProblemDetail body = problem(ex.status(), ex.title(), ex.getMessage(), ex.errorCode());
        ex.properties().forEach(body::setProperty);
        return respond(ex.status(), body, null);
    }

    @ExceptionHandler(InvalidNationalIdException.class)
    public ResponseEntity<ProblemDetail> invalidNationalId(InvalidNationalIdException ex) {
        log.info("KYC national ID refused (shape only, the number is not logged)");
        return respond(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, "Check the ID number",
                "That ID number doesn't look right: " + ex.getMessage() + ".", "national_id_invalid"), null);
    }

    @ExceptionHandler(KycImageRejectedException.class)
    public ResponseEntity<ProblemDetail> imageRejected(KycImageRejectedException ex) {
        log.info("KYC image refused: {} ({})", ex.reason().code(), ex.getMessage());
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Check the photo",
                switch (ex.reason()) {
                    case TOO_SMALL -> "That photo is too small to read. Move closer and retake it.";
                    case TOO_MANY_PIXELS -> "That photo is too large. Retake it at a lower resolution.";
                    default -> "That doesn't look like a camera photo we can use. Retake it and try again.";
                },
                "kyc_file_invalid");
        body.setProperty("reason", ex.reason().code());
        return respond(HttpStatus.BAD_REQUEST, body, null);
    }

    @ExceptionHandler(KycUploadBusyException.class)
    public ResponseEntity<ProblemDetail> busy(KycUploadBusyException ex) {
        log.warn("KYC upload refused: every decode slot busy");
        ProblemDetail body = problem(HttpStatus.SERVICE_UNAVAILABLE, "Busy",
                "We're processing a lot of photos right now. Please send it again in a moment.", "kyc_upload_busy");
        body.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, body, Long.toString(ex.retryAfterSeconds()));
    }

    /**
     * Same answer as the global handler's {@code malformed_request}, minus
     * its log line: Jackson's message can quote the offending token, and in
     * a KYC body that token can be the ID number or the address.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.info("KYC request body unreadable (parser detail withheld: it can quote the ID or the address)");
        return respond(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "We couldn't read your request. Please check the format and try again.", "malformed_request"), null);
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ProblemDetail> notRegistered(ProfileNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, problem(HttpStatus.NOT_FOUND, "Not registered",
                "We couldn't find a registered account for this login. Please register first.",
                "customer_not_registered"), null);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String errorCode) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(PROBLEM_TYPE);
        body.setTitle(title);
        body.setDetail(detail);
        body.setProperty("errorCode", errorCode);
        return body;
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, ProblemDetail body, String retryAfter) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(body);
    }
}
