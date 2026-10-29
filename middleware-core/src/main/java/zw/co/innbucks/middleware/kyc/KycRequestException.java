package zw.co.innbucks.middleware.kyc;

import org.springframework.http.HttpStatus;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every refusal the KYC endpoints answer, as one type carrying its status,
 * {@code errorCode} and extra problem properties — the factories below are
 * the whole vocabulary, so a new refusal is added here or not at all.
 * Rendered by {@code KycExceptionHandler} in the house ProblemDetail shape.
 *
 * <p>{@link #getMessage()} is the customer-facing detail: it never carries an
 * ID number, an address or file content.
 */
public class KycRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String title;
    private final Map<String, Object> properties;

    private KycRequestException(HttpStatus status, String errorCode, String title, String detail,
                                Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
        this.properties = properties;
    }

    /** Unknown id, or another customer's: one answer, so ids cannot be probed. */
    public static KycRequestException submissionNotFound() {
        return new KycRequestException(HttpStatus.NOT_FOUND, "kyc_submission_not_found", "Not found",
                "We couldn't find that verification request.", Map.of());
    }

    /** One open submission per customer; the app resumes this one. */
    public static KycRequestException submissionOpen(UUID submissionId) {
        return new KycRequestException(HttpStatus.CONFLICT, "kyc_submission_open", "Request already open",
                "You already have a verification request in progress. Continue that one.",
                Map.of("submissionId", submissionId.toString()));
    }

    /** The customer is on the highest tier: there is nothing to apply for. */
    public static KycRequestException notAvailable() {
        return new KycRequestException(HttpStatus.CONFLICT, "kyc_not_available", "Nothing to upgrade",
                "Your account is already on the highest level.", Map.of());
    }

    public static KycRequestException targetInvalid(String detail) {
        return new KycRequestException(HttpStatus.BAD_REQUEST, "kyc_target_invalid", "Check the level",
                detail, Map.of());
    }

    public static KycRequestException addressInvalid(String detail) {
        return new KycRequestException(HttpStatus.BAD_REQUEST, "kyc_address_invalid", "Check the address",
                detail, Map.of());
    }

    public static KycRequestException fileKindInvalid() {
        return new KycRequestException(HttpStatus.BAD_REQUEST, "kyc_file_kind_invalid", "Unknown file",
                "The file must be one of id_front, id_back, selfie or proof_of_address.", Map.of());
    }

    /** A kind the target tier does not need, or (after a rejection) one not listed to redo. */
    public static KycRequestException fileNotNeeded(KycFileKind kind, Collection<KycFileKind> uploadable) {
        return new KycRequestException(HttpStatus.BAD_REQUEST, "kyc_file_not_needed", "File not needed",
                "This request doesn't need that file now.",
                Map.of("kind", kind.code(), "uploadable", KycRequirements.codes(uploadable)));
    }

    /** Files or a submit on a round the customer can no longer change. */
    public static KycRequestException notOpen(KycSubmissionStatus status) {
        return new KycRequestException(HttpStatus.CONFLICT, "kyc_submission_not_open", "Request closed",
                status == KycSubmissionStatus.EXPIRED
                        ? "This verification request expired. Start a new one."
                        : "This verification request can't be changed any more.",
                Map.of("submissionStatus", status.dbValue()));
    }

    public static KycRequestException incomplete(List<String> missing) {
        return new KycRequestException(HttpStatus.UNPROCESSABLE_ENTITY, "kyc_incomplete",
                "Something is missing", "Add the missing files, then submit again.",
                Map.of("missing", missing));
    }

    /** Over the byte cap — from Content-Length before reading, or while reading a chunked body. */
    public static KycRequestException fileTooLarge(int maxBytes) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("maxBytes", maxBytes);
        return new KycRequestException(HttpStatus.PAYLOAD_TOO_LARGE, "kyc_file_too_large", "File too large",
                "That photo is too large. Retake it or send it at a lower size.", props);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
