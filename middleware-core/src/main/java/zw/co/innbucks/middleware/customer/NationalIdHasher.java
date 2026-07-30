package zw.co.innbucks.middleware.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Keyed hashing of national IDs for at-rest storage. Uses <b>HMAC-SHA256</b>
 * with a per-deployment secret — not plain SHA-256 — so that an attacker who
 * exfiltrates the {@code national_id_hash} column cannot rainbow-table or
 * dedup national IDs without also holding the deployment secret.
 *
 * <p>Fails fast at startup if the key is missing or weaker than 32 bytes, the
 * same bar as the JWT signing key. The key is trimmed (stray whitespace from
 * env vars is a known footgun in this codebase).
 */
@Component
public class NationalIdHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] key;

    public NationalIdHasher(@Value("${innbucks.auth.national-id-hmac-key:}") String hmacKey) {
        String trimmed = hmacKey == null ? "" : hmacKey.trim();
        if (trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "innbucks.auth.national-id-hmac-key (env NATIONAL_ID_HMAC_KEY) must be set and at "
                    + "least " + MIN_KEY_LENGTH + " characters — it keys the HMAC over national IDs; "
                    + "without it the hash degrades to rainbow-table-vulnerable plain hashing.");
        }
        this.key = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256 of {@code nationalId}, lower-case hex (64 chars). */
    public String hash(String nationalId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(nationalId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
