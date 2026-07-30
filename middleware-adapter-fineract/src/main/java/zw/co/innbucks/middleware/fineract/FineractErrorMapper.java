package zw.co.innbucks.middleware.fineract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreBankingException;
import zw.co.innbucks.middleware.corebanking.exception.CoreClientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Maps Fineract failures onto the core-neutral taxonomy. The READ/WRITE split
 * is the whole point:
 *
 * <ul>
 *   <li><b>Reads</b>: 5xx → {@link CoreServerException}; any I/O failure →
 *       {@link CoreTransientException}. Both retryable.</li>
 *   <li><b>Writes</b>: a 5xx RESPONSE means the command reached Fineract and
 *       died there — rollback is likely but unprovable, so →
 *       {@link CoreUnknownOutcomeException} (park, reconcile). HTTP 425 (the
 *       idempotent command is still under processing upstream) is the same
 *       ambiguity. Only failures PROVABLY before the request left (connect
 *       refused / unknown host / connect timeout) map to
 *       {@link CoreTransientException}; a READ timeout mid-write is
 *       unprovable → {@link CoreUnknownOutcomeException}.</li>
 *   <li>401 → {@link CoreAuthException} (our AppUser creds — ops incident).
 *       Fineract uses 403 for BUSINESS rule vetoes (insufficient balance,
 *       wrong account state), so 403 with a domain-error body →
 *       {@link CoreClientException}; a bare 403 → auth.</li>
 * </ul>
 */
final class FineractErrorMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FineractErrorMapper() {
    }

    static CoreBankingException mapReadFailure(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String detail = errorDetail(ex);
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract rejected the read credentials: " + detail, ex);
        }
        if (status == 403) {
            return forbidden(detail, ex);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT, "Fineract rejected the request (" + status + "): " + detail, ex);
        }
        return new CoreServerException(CoreProvider.FINERACT, "Fineract read failed (" + status + "): " + detail, ex);
    }

    static CoreBankingException mapWriteFailure(RestClientResponseException ex, TxRef txRef) {
        int status = ex.getStatusCode().value();
        String detail = errorDetail(ex);
        if (status == 401) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract rejected the write credentials: " + detail, ex);
        }
        if (status == 403) {
            return forbidden(detail, ex);
        }
        if (status == 425) {
            // Fineract's idempotency layer: the SAME key's original command is
            // still executing. The outcome exists but isn't known yet.
            return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                    "Fineract reports the command is still under processing (425)", ex);
        }
        if (status >= 400 && status < 500) {
            return new CoreClientException(CoreProvider.FINERACT, "Fineract rejected the command (" + status + "): " + detail, ex);
        }
        // The command reached Fineract and errored — rollback likely but unprovable.
        return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                "Fineract write returned " + status + " after the command was sent: " + detail, ex);
    }

    static CoreBankingException mapReadIoFailure(ResourceAccessException ex) {
        return new CoreTransientException(CoreProvider.FINERACT,
                "Fineract unreachable: " + rootMessage(ex), ex);
    }

    static CoreBankingException mapWriteIoFailure(ResourceAccessException ex, TxRef txRef) {
        if (provablyNeverSent(ex)) {
            return new CoreTransientException(CoreProvider.FINERACT,
                    "Fineract unreachable before the command was sent: " + rootMessage(ex), ex);
        }
        // Read-timeout / connection-reset mid-flight: the command MAY have applied.
        return new CoreUnknownOutcomeException(CoreProvider.FINERACT, txRef,
                "I/O failure with the command possibly in flight: " + rootMessage(ex), ex);
    }

    /**
     * Connect-phase failures guarantee no request bytes reached Fineract.
     * (java.net names connect timeouts "Connect timed out" vs read timeouts
     * "Read timed out" — the connect check is exact-cause-based first.)
     */
    private static boolean provablyNeverSent(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("connect timed out")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static CoreBankingException forbidden(String detail, RestClientResponseException ex) {
        // Fineract's domain-rule vetoes (insufficient balance, invalid account
        // state) come back 403 WITH a structured error body; permission/auth
        // problems carry permission-flavoured codes or no error list at all.
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("permission") || lower.contains("unauthorized") || lower.contains("authorization")) {
            return new CoreAuthException(CoreProvider.FINERACT, "Fineract authorization failed: " + detail, ex);
        }
        return new CoreClientException(CoreProvider.FINERACT, "Fineract vetoed the request: " + detail, ex);
    }

    /** First error's globalisation code + message from Fineract's error envelope; body preview as fallback. */
    static String errorDetail(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return ex.getStatusText();
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode errors = root.path("errors");
            JsonNode first = errors.isArray() && !errors.isEmpty() ? errors.get(0) : root;
            String code = first.path("userMessageGlobalisationCode").asText("");
            String message = first.path("defaultUserMessage").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return (code.isBlank() ? "" : code + ": ") + message;
            }
        } catch (Exception ignored) {
            // fall through to the raw preview
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }
}
