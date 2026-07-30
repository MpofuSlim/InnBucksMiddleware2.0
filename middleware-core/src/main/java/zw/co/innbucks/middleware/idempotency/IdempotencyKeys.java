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
        if (scope == null || scope.isBlank() || rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("scope and rawKey must be non-blank");
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
}
