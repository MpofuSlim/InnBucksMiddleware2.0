package zw.co.innbucks.middleware.kyc.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The AES-256 keys that seal KYC data at rest, read once at boot from a key
 * FILE — never from an environment variable.
 *
 * <p><b>Why a file, and why it must live apart from everything else.</b> The
 * root {@code .env} is copied into every cell backup by
 * {@code deploy/backup-cell.sh}, right next to {@code middleware-cluster.sql.gz}.
 * A key kept there would ride along with the ciphertext it protects, and the
 * encryption would protect nothing a backup thief cares about. The file is
 * mounted read-only into the container from a host path outside the repo and
 * outside any backup target; the backup script refuses to copy it.
 *
 * <p>Format — strict, every other line refuses boot:
 * <pre>
 *   # comments and blank lines are ignored
 *   current=v2
 *   v1=&lt;base64 of 32 random bytes&gt;    # openssl rand -base64 32
 *   v2=&lt;base64 of 32 random bytes&gt;
 * </pre>
 * {@code current} seals everything written from now on; every listed key can
 * still open what it sealed. Rotation is "add v{n+1}, point current at it,
 * restart" — old rows keep opening with their own key until re-sealed, so a
 * retired key may be removed only once nothing carries its prefix (runbook).
 *
 * <p>No message produced here ever contains key material.
 */
public final class KycKeyRing {

    private static final Pattern KEY_ID = Pattern.compile("^v[1-9][0-9]{0,5}$");
    static final int KEY_BYTES = 32;

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    KycKeyRing(String currentKeyId, Map<String, SecretKey> keys) {
        if (!keys.containsKey(currentKeyId)) {
            throw new KycKeyRingException("current key " + currentKeyId + " is not in the ring");
        }
        this.currentKeyId = currentKeyId;
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    /** Reads and validates the key file; throws {@link KycKeyRingException} on anything doubtful. */
    public static KycKeyRing load(Path file) {
        if (file == null) {
            throw new KycKeyRingException("no key file configured (KYC_KEYS_FILE)");
        }
        if (!Files.isRegularFile(file)) {
            throw new KycKeyRingException("key file " + file + " does not exist or is not a regular file");
        }
        if (!Files.isReadable(file)) {
            throw new KycKeyRingException("key file " + file + " is not readable by this process");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new KycKeyRingException("key file " + file + " could not be read: " + ex.getClass().getSimpleName());
        }
        return parse(file.toString(), content);
    }

    /** Parses key-file content. {@code source} names it in error messages. */
    public static KycKeyRing parse(String source, String content) {
        String current = null;
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            int lineNo = i + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new KycKeyRingException(source + " line " + lineNo + ": expected name=value");
            }
            String name = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if ("current".equals(name)) {
                if (current != null) {
                    throw new KycKeyRingException(source + " line " + lineNo + ": 'current' given twice");
                }
                current = value;
                continue;
            }
            if (!KEY_ID.matcher(name).matches()) {
                throw new KycKeyRingException(source + " line " + lineNo
                        + ": unknown entry (expected 'current' or a key id like v1)");
            }
            if (keys.containsKey(name)) {
                throw new KycKeyRingException(source + " line " + lineNo + ": key " + name + " given twice");
            }
            keys.put(name, decodeKey(source, lineNo, name, value));
        }
        if (keys.isEmpty()) {
            throw new KycKeyRingException(source + ": no keys (add v1=<openssl rand -base64 32>)");
        }
        if (current == null || current.isEmpty()) {
            throw new KycKeyRingException(source + ": no 'current=' line naming the key that seals new data");
        }
        if (!keys.containsKey(current)) {
            throw new KycKeyRingException(source + ": current=" + current + " names no key in the file");
        }
        return new KycKeyRing(current, keys);
    }

    private static SecretKey decodeKey(String source, int lineNo, String name, String value) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new KycKeyRingException(source + " line " + lineNo + ": key " + name + " is not valid base64");
        }
        if (raw.length != KEY_BYTES) {
            throw new KycKeyRingException(source + " line " + lineNo + ": key " + name + " decodes to "
                    + raw.length + " bytes; AES-256 needs exactly " + KEY_BYTES + " (openssl rand -base64 32)");
        }
        boolean allSame = true;
        for (byte b : raw) {
            allSame &= b == raw[0];
        }
        if (allSame) {
            throw new KycKeyRingException(source + " line " + lineNo + ": key " + name
                    + " is one byte repeated — not a random key");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * A reason to worry about the file's permissions, or empty. Advisory only:
     * inside a container the owner and mode are whatever the bind mount
     * presents, so this warns rather than refusing.
     */
    public static Optional<String> permissionWarning(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.OTHERS_READ)) {
                return Optional.of("key file " + file + " is world-readable; make it 0400/0440 owned by the "
                        + "container user");
            }
            return Optional.empty();
        } catch (IOException | UnsupportedOperationException ex) {
            return Optional.empty();
        }
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    SecretKey current() {
        return keys.get(currentKeyId);
    }

    Optional<SecretKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    /** The ids in the ring, for the boot log. Never the keys. */
    public Set<String> keyIds() {
        return keys.keySet();
    }

    @Override
    public String toString() {
        return "KycKeyRing[current=" + currentKeyId + ", keys=" + keys.keySet() + "]";
    }
}
