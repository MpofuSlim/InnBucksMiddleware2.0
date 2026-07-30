package zw.co.innbucks.middleware.otp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Keyed hashing of OTP codes for at-rest storage. <b>HMAC-SHA256, never a
 * bare hash</b>: an OTP is 6 digits — a million-value space — so an unkeyed
 * SHA-256 of it is trivially reversed from a DB read with one rainbow pass.
 * Keying with a per-deployment secret means a leaked {@code otp_challenge}
 * table is useless without the key. Same design as {@code NationalIdHasher}.
 *
 * <p>Fails fast at startup if the key is missing or shorter than 32 chars.
 * The key is trimmed (stray whitespace from env vars is a known footgun in
 * this codebase).
 */
@Component
public class OtpHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] key;

    public OtpHasher(@Value("${innbucks.otp.hmac-secret:}") String hmacSecret) {
        String trimmed = hmacSecret == null ? "" : hmacSecret.trim();
        if (trimmed.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "innbucks.otp.hmac-secret (env OTP_HMAC_SECRET) must be set and at least "
                    + MIN_KEY_LENGTH + " characters — it keys the HMAC over stored OTP codes; "
                    + "without it a 6-digit code hash is trivially reversible from a DB read.");
        }
        this.key = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256 of {@code code}, lower-case hex (64 chars). */
    public String hash(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
