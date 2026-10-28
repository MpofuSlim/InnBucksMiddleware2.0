package zw.co.innbucks.middleware.kyc.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The key file is parsed strictly, and no refusal ever repeats key material. */
class KycKeyRingTest {

    private final String k1 = KycTestKeys.newKey();
    private final String k2 = KycTestKeys.newKey();

    @Test
    void parsesCurrentAndPreviousKeys_ignoringCommentsAndBlankLines() {
        KycKeyRing ring = KycKeyRing.parse("f", """
                # KYC keys for the ZW cell
                current=v2

                v1=%s
                  v2 = %s
                """.formatted(k1, k2));

        assertThat(ring.currentKeyId()).isEqualTo("v2");
        assertThat(ring.keyIds()).containsExactly("v1", "v2");
        assertThat(ring.toString()).doesNotContain(k1).doesNotContain(k2);
    }

    @Test
    void refusesEveryDoubtfulFile_withoutRepeatingTheKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String zeroKey = Base64.getEncoder().encodeToString(new byte[32]);
        String[][] cases = {
                {"", "no keys"},
                {"v1=" + k1, "no 'current="},
                {"current=v2\nv1=" + k1, "names no key"},
                {"current=v1\ncurrent=v1\nv1=" + k1, "'current' given twice"},
                {"current=v1\nv1=" + k1 + "\nv1=" + k2, "given twice"},
                {"current=v1\nv1=not base64!!", "not valid base64"},
                {"current=v1\nv1=" + shortKey, "16 bytes"},
                {"current=v1\nv1=" + zeroKey, "one byte repeated"},
                {"current=v1\nv1=" + k1 + "\nkey=" + k2, "unknown entry"},
                {"current=v1\nV1=" + k1, "unknown entry"},
                {"current=v1\njust a line", "expected name=value"},
        };
        for (String[] c : cases) {
            assertThatThrownBy(() -> KycKeyRing.parse("kyc-keys", c[0]))
                    .as(c[1])
                    .isInstanceOf(KycKeyRingException.class)
                    .hasMessageContaining(c[1])
                    .hasMessageNotContaining(k1)
                    .hasMessageNotContaining(k2);
        }
    }

    @Test
    void loadsFromAFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kyc-keys");
        Files.writeString(file, "current=v1\nv1=" + k1 + "\n");

        assertThat(KycKeyRing.load(file).currentKeyId()).isEqualTo("v1");
    }

    @Test
    void aMissingFileOrPathRefusesTheBoot(@TempDir Path dir) {
        assertThatThrownBy(() -> KycKeyRing.load(dir.resolve("absent")))
                .isInstanceOf(KycKeyRingException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> KycKeyRing.load(dir))
                .isInstanceOf(KycKeyRingException.class).hasMessageContaining("not a regular file");
        assertThatThrownBy(() -> KycKeyRing.load(null))
                .isInstanceOf(KycKeyRingException.class).hasMessageContaining("KYC_KEYS_FILE");
    }

    @Test
    void aWorldReadableFileIsWarnedAbout(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kyc-keys");
        Files.writeString(file, "current=v1\nv1=" + k1 + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        assertThat(KycKeyRing.permissionWarning(file)).hasValueSatisfying(w -> assertThat(w).contains("world-readable"));

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        assertThat(KycKeyRing.permissionWarning(file)).isEmpty();
    }
}
