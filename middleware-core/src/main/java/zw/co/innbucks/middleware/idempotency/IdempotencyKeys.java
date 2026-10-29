package zw.co.innbucks.middleware.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Namespacing for caller-supplied Idempotency-Keys. Inbound keys are
 * client-chosen and would otherwise be GLOBALLY scoped — a buggy or malicious
 * client reusing another customer's key could replay that customer's cached
 * response, and the key is also forwarded upstream as the dedup handle.
 * Hashing the scope (customer UUID, or normalized MSISDN pre-auth) together
 * with the raw key makes collisions across scopes impossible while keeping
 * retries by the same caller deterministic.
 *
 * <p>The output (64 hex chars) is used verbatim as: our idempotency_record
 * key, the ledger external_ref, and the upstream core's Idempotency-Key.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String namespaced(String scope, String rawKey) {
        requirePresent(rawKey);
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope must be non-blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Unit-separator between the parts: without it ("ab","c") and
            // ("a","bc") would hash identically across scopes.
            byte[] hashed = digest.digest((scope + "\u001F" + rawKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Refuses a key the caller sent blank (400 {@code missing_header}, the
     * same answer as no header at all). A blank SCOPE stays a programming
     * error; a blank KEY is the caller's. Cheap enough to call before any
     * other work, which is where a caller that spends budgets should call it.
     */
    public static void requirePresent(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new BlankIdempotencyKeyException();
        }
    }
}
