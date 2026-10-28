package zw.co.innbucks.middleware.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Fingerprints an idempotent request's body for {@code
 * idempotency_record.request_fingerprint} — the value a retry is compared
 * against to tell "same request" (replay) from "same key, different body"
 * (422). <b>Keyed HMAC-SHA256, never a bare hash</b>: {@code /register}'s
 * body carries the customer's national ID next to an MSISDN and names an
 * attacker can already know, so an unkeyed SHA-256 kept for the record's TTL
 * (and in every backup taken meanwhile) was a brute-forceable hash of a
 * low-entropy secret — exactly what {@code NationalIdHasher} exists to
 * prevent for the {@code customer} row.
 *
 * <p><b>The key is DERIVED from {@code NATIONAL_ID_HMAC_KEY}</b>, not a new
 * secret: {@code HMAC(nidKey, 0xFF ‖ "innbucks/idempotency-fingerprint/v1")}.
 * <ul>
 *   <li>Custody fits: the most sensitive thing a fingerprint protects is a
 *       national ID, so it is protected by exactly the key that protects
 *       {@code customer.national_id_hash} — no better, no worse, and no new
 *       secret to provision per cell.</li>
 *   <li>Rotation fits: that key is effectively never rotated (rotating it
 *       orphans every stored national-ID hash). If it ever is, records written
 *       before the switch stop matching for the rest of their TTL: a same-key
 *       retry answers 422 {@code idempotency_conflict} instead of a replay. It
 *       never runs the work twice — fail-safe — but rotate it only after
 *       draining {@code idempotency_record} (wait one TTL).</li>
 *   <li>The 0xFF prefix is the domain separation. {@code NationalIdHasher}
 *       HMACs a string's UTF-8 bytes under the same parent key, and UTF-8
 *       never contains 0xFF — so no national ID a registrant types (the field
 *       is free text up to 50 chars) can make that hasher emit this sub-key
 *       into {@code customer.national_id_hash}. With a plain-text label it
 *       could: registering the label itself as a "national ID" would have
 *       stored the sub-key in the clear.</li>
 * </ul>
 *
 * <p>Stored as {@code "v2:" + base64url(hmac)} (46 chars — the column is
 * VARCHAR(64), so hex would not fit with the prefix and no migration is
 * needed). Unprefixed values are the pre-keyed SHA-256 hex and still MATCH, so
 * a record written before this deploy replays correctly until it expires;
 * that branch can go once every pre-deploy record is past its TTL.
 */
@Component
public class IdempotencyFingerprinter {

    static final String VERSION_PREFIX = "v2:";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_LENGTH = 32;
    private static final String SUB_KEY_LABEL = "innbucks/idempotency-fingerprint/v1";

    private final byte[] subKey;
    private final ObjectMapper objectMapper;

    public IdempotencyFingerprinter(@Value("${innbucks.auth.national-id-hmac-key:}") String nationalIdHmacKey,
                                    ObjectMapper objectMapper) {
        String trimmed = nationalIdHmacKey == null ? "" : nationalIdHmacKey.trim();
        if (trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "innbucks.auth.national-id-hmac-key (env NATIONAL_ID_HMAC_KEY) must be set and at least "
                    + MIN_KEY_LENGTH + " characters — it keys the idempotency fingerprint, which covers "
                    + "request bodies carrying national IDs.");
        }
        byte[] label = SUB_KEY_LABEL.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[label.length + 1];
        input[0] = (byte) 0xFF;
        System.arraycopy(label, 0, input, 1, label.length);
        this.subKey = hmac(trimmed.getBytes(StandardCharsets.UTF_8), input);
        this.objectMapper = objectMapper;
    }

    /** Canonicalises the body once; the result both writes a new record and checks a stored one. */
    public RequestFingerprint fingerprint(Object body) {
        byte[] canonical;
        try {
            canonical = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to fingerprint idempotency request body", ex);
        }
        String value = VERSION_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(subKey, canonical));
        return new RequestFingerprint(value, canonical);
    }

    /** One request's fingerprint: {@link #value()} to store, {@link #matches} to compare. */
    public static final class RequestFingerprint {

        private final String value;
        private final byte[] canonicalBody;

        private RequestFingerprint(String value, byte[] canonicalBody) {
            this.value = value;
            this.canonicalBody = canonicalBody;
        }

        /** What a NEW record stores — always the keyed form. */
        public String value() {
            return value;
        }

        /** Whether a stored fingerprint is this same request. Constant-time either way. */
        public boolean matches(String stored) {
            if (stored == null) {
                return false;
            }
            if (stored.startsWith(VERSION_PREFIX)) {
                return constantTimeEquals(stored, value);
            }
            // LEGACY: records written before the keyed fingerprint (bare SHA-256
            // hex). They live at most the idempotency TTL; drop this branch once
            // every pre-deploy record has expired.
            return constantTimeEquals(stored, legacySha256Hex(canonicalBody));
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String legacySha256Hex(byte[] canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
