package zw.co.innbucks.middleware.kyc.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The versioned AES-256-GCM envelope every piece of KYC data is stored in:
 * the typed national ID, the typed address and every image.
 *
 * <pre>
 *   binary (bytea):  ASCII "&lt;keyId&gt;:" || nonce(12) || ciphertext || tag(16)
 *   text:            "&lt;keyId&gt;:" + base64(nonce || ciphertext || tag)
 * </pre>
 *
 * <ul>
 *   <li><b>Key id prefix</b> ({@code v1:}): which key in the {@link KycKeyRing}
 *       sealed it, so a rotation never has to re-seal everything at once.</li>
 *   <li><b>Random 96-bit nonce per value</b> from {@link SecureRandom}. Never a
 *       counter: one key seals every row, across restarts.</li>
 *   <li><b>AAD</b> ({@link KycAad}): the row and column the value belongs to,
 *       so a blob moved between rows does not open.</li>
 * </ul>
 *
 * <p>Nothing here logs, and no exception carries plaintext or key material.
 */
public final class KycCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final int MAX_KEY_ID_LENGTH = 7;
    private static final Pattern KEY_ID = Pattern.compile("^v[1-9][0-9]{0,5}$");

    private final KycKeyRing keyRing;
    private final SecureRandom random;

    public KycCipher(KycKeyRing keyRing) {
        this(keyRing, new SecureRandom());
    }

    KycCipher(KycKeyRing keyRing, SecureRandom random) {
        this.keyRing = keyRing;
        this.random = random;
    }

    /** Seals {@code plaintext} under the CURRENT key, bound to {@code aad}. */
    public byte[] seal(byte[] plaintext, KycAad aad) {
        String keyId = keyRing.currentKeyId();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = run(Cipher.ENCRYPT_MODE, keyRing.current(), nonce, aad, plaintext, 0, plaintext.length);
        byte[] prefix = (keyId + ":").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[prefix.length + NONCE_BYTES + sealed.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(nonce, 0, out, prefix.length, NONCE_BYTES);
        System.arraycopy(sealed, 0, out, prefix.length + NONCE_BYTES, sealed.length);
        return out;
    }

    /** Opens a binary envelope with the key its prefix names; refuses any other binding. */
    public byte[] open(byte[] envelope, KycAad aad) {
        int colon = prefixEnd(envelope);
        String keyId = new String(envelope, 0, colon, StandardCharsets.US_ASCII);
        int bodyStart = colon + 1;
        if (envelope.length - bodyStart < NONCE_BYTES + TAG_BITS / 8) {
            throw new KycCryptoException("envelope is too short to hold a nonce and a tag");
        }
        SecretKey key = keyRing.key(keyId).orElseThrow(() -> new KycCryptoException(
                "sealed under key " + keyId + ", which is not in the key file — restore it to read this row"));
        byte[] nonce = Arrays.copyOfRange(envelope, bodyStart, bodyStart + NONCE_BYTES);
        int offset = bodyStart + NONCE_BYTES;
        return run(Cipher.DECRYPT_MODE, key, nonce, aad, envelope, offset, envelope.length - offset);
    }

    public String sealText(String plaintext, KycAad aad) {
        byte[] envelope = seal(plaintext.getBytes(StandardCharsets.UTF_8), aad);
        int colon = prefixEnd(envelope);
        return new String(envelope, 0, colon + 1, StandardCharsets.US_ASCII)
                + Base64.getEncoder().encodeToString(Arrays.copyOfRange(envelope, colon + 1, envelope.length));
    }

    public String openText(String envelope, KycAad aad) {
        int colon = envelope.indexOf(':');
        if (colon <= 0 || colon > MAX_KEY_ID_LENGTH) {
            throw new KycCryptoException("not a KYC envelope (no key id prefix)");
        }
        byte[] body;
        try {
            body = Base64.getDecoder().decode(envelope.substring(colon + 1));
        } catch (IllegalArgumentException ex) {
            throw new KycCryptoException("envelope body is not valid base64");
        }
        byte[] prefix = envelope.substring(0, colon + 1).getBytes(StandardCharsets.US_ASCII);
        byte[] binary = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, binary, 0, prefix.length);
        System.arraycopy(body, 0, binary, prefix.length, body.length);
        return new String(open(binary, aad), StandardCharsets.UTF_8);
    }

    /** The key id a binary envelope was sealed with (for rotation bookkeeping). */
    public static String keyIdOf(byte[] envelope) {
        return new String(envelope, 0, prefixEnd(envelope), StandardCharsets.US_ASCII);
    }

    /** Index of the ':' ending a valid key-id prefix. */
    private static int prefixEnd(byte[] envelope) {
        int limit = Math.min(envelope.length, MAX_KEY_ID_LENGTH + 1);
        for (int i = 0; i < limit; i++) {
            if (envelope[i] == ':') {
                String keyId = new String(envelope, 0, i, StandardCharsets.US_ASCII);
                if (!KEY_ID.matcher(keyId).matches()) {
                    break;
                }
                return i;
            }
        }
        throw new KycCryptoException("not a KYC envelope (no key id prefix)");
    }

    private static byte[] run(int mode, SecretKey key, byte[] nonce, KycAad aad,
                              byte[] input, int offset, int length) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.bytes());
            return cipher.doFinal(input, offset, length);
        } catch (AEADBadTagException ex) {
            // Wrong key, wrong row/column binding, or altered bytes: all the
            // same answer, and none of them is a reason to show anything.
            throw new KycCryptoException("ciphertext does not open under its key and binding "
                    + "(altered, moved between rows, or sealed with a different key of the same id)");
        } catch (GeneralSecurityException ex) {
            throw new KycCryptoException("AES-GCM is unavailable: " + ex.getClass().getSimpleName());
        }
    }
}
