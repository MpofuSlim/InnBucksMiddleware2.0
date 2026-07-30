package zw.co.innbucks.middleware.stepup;

import zw.co.innbucks.middleware.ledger.LedgerTransactionType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical fingerprint of one money movement, for step-up binding: a
 * STEP_UP verification token carries this value in its {@code txn_fp} claim
 * and approves ONLY the movement that re-derives the same fingerprint.
 *
 * <p>The customer id is part of the input, so a token minted for one
 * customer's movement can never match another customer's — even an
 * identical-looking transfer. Fields are joined with an ASCII unit
 * separator (0x1F, same convention as {@code IdempotencyKeys}) so no crafted field values can
 * collide across boundaries, then SHA-256'd to a fixed-width hex string
 * that is safe to echo to the client in the 403 response.
 */
public final class TxnFingerprint {

    private static final String SEPARATOR = "\u001F";

    private TxnFingerprint() {
    }

    public static String of(UUID customerId,
                            LedgerTransactionType type,
                            String sourceAccountId,
                            String targetAccountId,
                            long amountMinor,
                            String currency) {
        String canonical = String.join(SEPARATOR,
                customerId.toString(),
                type.name(),
                sourceAccountId == null ? "" : sourceAccountId,
                targetAccountId == null ? "" : targetAccountId,
                Long.toString(amountMinor),
                currency == null ? "" : currency);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JCA spec on every JVM; unreachable.
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
