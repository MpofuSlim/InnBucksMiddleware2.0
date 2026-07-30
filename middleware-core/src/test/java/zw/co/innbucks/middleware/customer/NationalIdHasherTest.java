package zw.co.innbucks.middleware.customer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NationalIdHasherTest {

    private static final String KEY = "unit-test-hmac-key-at-least-32-bytes-long!!";
    // Plain SHA-256("12345678") — what the old, rainbow-table-vulnerable code produced.
    private static final String PLAIN_SHA256_OF_12345678 =
            "ef797c8118f02dfb649607dd5d3f8c7623048c9c063d532cc95c5ed7a898a64f";

    @Test
    void hashIsDeterministicForSameKeyAndInput() {
        NationalIdHasher hasher = new NationalIdHasher(KEY);
        assertThat(hasher.hash("12345678")).isEqualTo(hasher.hash("12345678"));
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        String a = new NationalIdHasher(KEY).hash("12345678");
        String b = new NationalIdHasher("a-totally-different-hmac-key-32+bytes!!").hash("12345678");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void isKeyedHmacNotPlainSha256() {
        String hmac = new NationalIdHasher(KEY).hash("12345678");
        assertThat(hmac).hasSize(64);                       // HMAC-SHA256 hex
        assertThat(hmac).isNotEqualTo(PLAIN_SHA256_OF_12345678);
    }

    @Test
    void failsFastWhenKeyMissingOrWeak() {
        assertThatThrownBy(() -> new NationalIdHasher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NationalIdHasher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NationalIdHasher("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
