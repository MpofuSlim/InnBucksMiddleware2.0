package zw.co.innbucks.middleware.auth.exception;

/**
 * Thrown when a request body or path field is structurally well-formed
 * (passed {@code @Valid}) but its value can't be parsed by the controller
 * before any business call happens — typically when the controller manually
 * calls {@code UUID.fromString} on a caller-supplied string.
 *
 * <p>Separate from a bare {@link IllegalArgumentException} on purpose:
 * AuthExceptionHandler maps this to a clean 400 {@code bad_request} while
 * letting genuine programmer-bug IAEs (e.g. {@code PinHasher.hash(null)})
 * bubble up to Spring's default 500 handler — which is the correct
 * observable behaviour for an internal contract violation. The previous
 * blanket IAE→400 handler masked those as client errors and hid real bugs
 * from error-rate dashboards.
 */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }
}
