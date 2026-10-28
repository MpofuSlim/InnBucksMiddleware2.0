package zw.co.innbucks.middleware.kyc.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The envelope: versioned key id, random nonce, AAD binding each value to its
 * row and column, rotation through previous keys — and every way of opening a
 * value wrongly refused.
 */
class KycCipherTest {

    private static final UUID SUBMISSION = UUID.fromString("b1c2d3e4-5f60-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID OTHER_SUBMISSION = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    private final String v1 = KycTestKeys.newKey();
    private final String v2 = KycTestKeys.newKey();

    private KycCipher cipher(String fileContent) {
        return new KycCipher(KycKeyRing.parse("test", fileContent));
    }

    @Test
    void binaryRoundTrip_carriesTheCurrentKeyIdAndNoPlaintext() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        byte[] plaintext = "a jpeg, really".getBytes(StandardCharsets.UTF_8);
        UUID fileId = UUID.randomUUID();

        byte[] envelope = cipher.seal(plaintext, KycAad.file(fileId));

        assertThat(new String(envelope, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("v1:");
        assertThat(KycCipher.keyIdOf(envelope)).isEqualTo("v1");
        // prefix + 12-byte nonce + ciphertext + 16-byte tag
        assertThat(envelope).hasSize(3 + 12 + plaintext.length + 16);
        assertThat(new String(envelope, StandardCharsets.ISO_8859_1)).doesNotContain("a jpeg");
        assertThat(cipher.open(envelope, KycAad.file(fileId))).isEqualTo(plaintext);
    }

    @Test
    void textRoundTrip() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        String envelope = cipher.sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));

        assertThat(envelope).startsWith("v1:").doesNotContain("631234567");
        assertThat(cipher.openText(envelope, KycAad.nationalId(SUBMISSION, 1))).isEqualTo("631234567X18");
    }

    @Test
    void everySealUsesAFreshNonce() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        String a = cipher.sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));
        String b = cipher.sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void aValueMovedToAnotherRowOrColumnDoesNotOpen() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        String sealed = cipher.sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));

        // another submission, another round, another column
        assertThatThrownBy(() -> cipher.openText(sealed, KycAad.nationalId(OTHER_SUBMISSION, 1)))
                .isInstanceOf(KycCryptoException.class);
        assertThatThrownBy(() -> cipher.openText(sealed, KycAad.nationalId(SUBMISSION, 2)))
                .isInstanceOf(KycCryptoException.class);
        assertThatThrownBy(() -> cipher.openText(sealed, KycAad.address(SUBMISSION)))
                .isInstanceOf(KycCryptoException.class);

        UUID fileA = UUID.randomUUID();
        byte[] photo = cipher.seal(new byte[]{1, 2, 3}, KycAad.file(fileA));
        assertThatThrownBy(() -> cipher.open(photo, KycAad.file(UUID.randomUUID())))
                .isInstanceOf(KycCryptoException.class)
                .hasMessageContaining("does not open");
    }

    @Test
    void aPreviousKeyStillOpensWhatItSealed_newDataUsesTheCurrentOne() {
        KycCipher before = cipher("current=v1\nv1=" + v1);
        String old = before.sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));

        KycCipher rotated = cipher("current=v2\nv1=" + v1 + "\nv2=" + v2);

        assertThat(rotated.openText(old, KycAad.nationalId(SUBMISSION, 1))).isEqualTo("631234567X18");
        assertThat(rotated.sealText("x", KycAad.address(SUBMISSION))).startsWith("v2:");
    }

    @Test
    void aRetiredKeyThatIsGoneFromTheFileIsNamedNotGuessed() {
        String old = cipher("current=v1\nv1=" + v1).sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));
        KycCipher withoutV1 = cipher("current=v2\nv2=" + v2);

        assertThatThrownBy(() -> withoutV1.openText(old, KycAad.nationalId(SUBMISSION, 1)))
                .isInstanceOf(KycCryptoException.class)
                .hasMessageContaining("key v1")
                .hasMessageNotContaining(v1)
                .hasMessageNotContaining("631234567");
    }

    @Test
    void theWrongKeyUnderTheSameIdDoesNotOpen() {
        String sealed = cipher("current=v1\nv1=" + v1).sealText("631234567X18", KycAad.nationalId(SUBMISSION, 1));
        KycCipher wrong = cipher("current=v1\nv1=" + v2);

        assertThatThrownBy(() -> wrong.openText(sealed, KycAad.nationalId(SUBMISSION, 1)))
                .isInstanceOf(KycCryptoException.class);
    }

    @Test
    void alteredBytesDoNotOpen() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        UUID fileId = UUID.randomUUID();
        byte[] envelope = cipher.seal("pixels".getBytes(StandardCharsets.UTF_8), KycAad.file(fileId));
        byte[] flipped = Arrays.copyOf(envelope, envelope.length);
        flipped[flipped.length - 20] ^= 0x01;

        assertThatThrownBy(() -> cipher.open(flipped, KycAad.file(fileId))).isInstanceOf(KycCryptoException.class);
    }

    @Test
    void somethingThatIsNotAnEnvelopeIsRefused() {
        KycCipher cipher = cipher("current=v1\nv1=" + v1);
        assertThatThrownBy(() -> cipher.open("plain bytes".getBytes(StandardCharsets.UTF_8),
                KycAad.file(UUID.randomUUID()))).isInstanceOf(KycCryptoException.class);
        assertThatThrownBy(() -> cipher.openText("631234567X18", KycAad.nationalId(SUBMISSION, 1)))
                .isInstanceOf(KycCryptoException.class);
        assertThatThrownBy(() -> cipher.open("v1:short".getBytes(StandardCharsets.UTF_8),
                KycAad.file(UUID.randomUUID()))).isInstanceOf(KycCryptoException.class);
    }
}
