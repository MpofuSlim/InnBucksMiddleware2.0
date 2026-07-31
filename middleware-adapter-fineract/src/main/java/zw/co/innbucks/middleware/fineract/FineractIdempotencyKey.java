package zw.co.innbucks.middleware.fineract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Compresses our idempotency keys to fit Fineract's storage.
 *
 * <p>Fineract persists the {@code Idempotency-Key} header into
 * {@code m_portfolio_command_source.idempotency_key}, which is
 * {@code VARCHAR(50)} (changelog 0061). Our keys are the 64-hex-char output of
 * {@code IdempotencyKeys.namespaced}, and the savings saga appends per-leg
 * suffixes ({@code :create}, {@code :approve}, {@code :activate}) on top — up
 * to 73 characters. Sending those verbatim overflows the column, and Fineract
 * surfaces it as a data-integrity error on EVERY write, so registration and
 * every money movement fail.
 *
 * <p>Truncating the key would be wrong: the leg suffix lives at the END, so
 * the three saga legs would collapse to the same key and Fineract would dedup
 * legs against each other. Re-hashing keeps them distinct.
 *
 * <p>128 bits of the digest, base64url without padding, is 22 characters —
 * comfortably inside the limit with room for the column to be the constraint
 * rather than us. Collision resistance is far beyond what dedup needs (the
 * birthday bound is 2^64 keys within one cell's retention window), and the
 * mapping is deterministic, which is the property retries actually depend on.
 *
 * <p>This lives in the ADAPTER, not in core: 50 characters is Fineract's
 * limit, not a property of our keys. Another core gets its own bound.
 */
final class FineractIdempotencyKey {

    /** Fineract's m_portfolio_command_source.idempotency_key width. */
    static final int MAX_LENGTH = 50;

    private static final int DIGEST_BYTES = 16;

    private FineractIdempotencyKey() {
    }

    static String forCore(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key must be non-blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[DIGEST_BYTES];
            System.arraycopy(digest, 0, truncated, 0, DIGEST_BYTES);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
