package zw.co.innbucks.middleware.auth.jwt;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM → RSA key parsing for the JWT RS256 path. Tolerates the shapes env-var
 * plumbing produces: real newlines, literal {@code \n} escapes (compose/k8s
 * single-line values), and stray whitespace. Fails loudly on anything that
 * isn't the expected PEM type — a misconfigured key must stop boot, not
 * surface as per-request 401s.
 */
public final class PemKeys {

    private PemKeys() {
    }

    /** Parses a PKCS#8 "BEGIN PRIVATE KEY" PEM into an RSA private key. */
    public static RSAPrivateKey parsePrivateKey(String pem) {
        byte[] der = decodePem(pem, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "innbucks.auth.private-key is not a valid PKCS#8 RSA private key "
                    + "(expected -----BEGIN PRIVATE KEY-----; convert PKCS#1 with "
                    + "'openssl pkcs8 -topk8 -nocrypt')", ex);
        }
    }

    /** Parses an X.509 "BEGIN PUBLIC KEY" PEM into an RSA public key. */
    public static RSAPublicKey parsePublicKey(String pem) {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "innbucks.auth.public-key is not a valid X.509 RSA public key "
                    + "(expected -----BEGIN PUBLIC KEY-----)", ex);
        }
    }

    private static byte[] decodePem(String pem, String expectedType) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("PEM " + expectedType + " is blank");
        }
        String normalized = pem.replace("\\n", "\n").trim();
        String begin = "-----BEGIN " + expectedType + "-----";
        String end = "-----END " + expectedType + "-----";
        if (!normalized.contains(begin) || !normalized.contains(end)) {
            throw new IllegalStateException(
                    "PEM does not carry the expected '" + begin + "' block");
        }
        String base64 = normalized
                .substring(normalized.indexOf(begin) + begin.length(), normalized.indexOf(end))
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("PEM " + expectedType + " body is not valid base64", ex);
        }
    }
}
