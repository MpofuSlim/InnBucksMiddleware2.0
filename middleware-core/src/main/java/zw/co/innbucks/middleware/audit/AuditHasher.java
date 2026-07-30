package zw.co.innbucks.middleware.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Computes the tamper-evidence HMACs for {@code audit_event} rows, keyed by
 * the per-deployment {@code innbucks.audit.hmac-secret}:
 *
 * <ul>
 *   <li>{@code row_hmac} — HMAC-SHA256 over a canonical rendering of the
 *       row's content. Proves the row's CONTENT is intact.</li>
 *   <li>{@code chain_hmac} — {@code HMAC(key, prev_chain_hmac ‖ row_hmac)},
 *       binding each row to its predecessor. Content HMACs alone cannot
 *       detect a row being DELETED or reordered (each survivor still
 *       self-verifies); the chain breaks at the first survivor after a
 *       deletion and cannot be repaired without the key.</li>
 * </ul>
 *
 * <p>Metadata JSON is canonicalized (parsed and re-serialized with sorted map
 * keys) on BOTH the write and verify paths, because Postgres {@code jsonb}
 * does not preserve key order — hashing the raw inbound string would produce
 * false tamper alarms on read-back.
 */
@Component
public class AuditHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] key;
    private final ObjectMapper canonicalMapper;

    public AuditHasher(@Value("${innbucks.audit.hmac-secret:}") String hmacSecret) {
        String trimmed = hmacSecret == null ? "" : hmacSecret.trim();
        if (trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "innbucks.audit.hmac-secret (env AUDIT_HMAC_SECRET) must be set and at least "
                    + MIN_KEY_LENGTH + " characters — it keys the tamper-evidence HMAC chain over "
                    + "audit_event; without it the audit trail is silently editable.");
        }
        this.key = trimmed.getBytes(StandardCharsets.UTF_8);
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String rowHmac(Instant occurredAt,
                          String country,
                          UUID customerId,
                          String action,
                          String outcome,
                          String correlationId,
                          String ipAddress,
                          String userAgent,
                          String deviceHash,
                          String canonicalMetadataJson) {
        String payload = String.join("\n",
                "v1",
                DateTimeFormatter.ISO_INSTANT.format(occurredAt),
                country,
                customerId == null ? "" : customerId.toString(),
                action,
                outcome,
                nullToEmpty(correlationId),
                nullToEmpty(ipAddress),
                nullToEmpty(userAgent),
                nullToEmpty(deviceHash),
                nullToEmpty(canonicalMetadataJson));
        return hmacHex(payload);
    }

    public String chainHmac(String previousChainHmac, String rowHmac) {
        return hmacHex(nullToEmpty(previousChainHmac) + rowHmac);
    }

    /**
     * Parse and re-serialize JSON with sorted map keys so the write path and
     * the jsonb read-back path hash the same bytes. Null/blank stays null.
     */
    public String canonicalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Object parsed = canonicalMapper.readValue(json, Object.class);
            return canonicalMapper.writeValueAsString(parsed);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize audit metadata JSON", ex);
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
