package zw.co.innbucks.middleware.otp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class OtpHasherTest {

    private static final String KEY = "test-otp-hmac-secret-at-least-32-bytes-long";

    @Test
    void hashIsDeterministicPerKey() {
        OtpHasher hasher = new OtpHasher(KEY);
        assertThat(hasher.hash("123456")).isEqualTo(hasher.hash("123456"));
        assertThat(hasher.hash("123456")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void differentCodesProduceDifferentHashes() {
        OtpHasher hasher = new OtpHasher(KEY);
        assertThat(hasher.hash("123456")).isNotEqualTo(hasher.hash("123457"));
    }

    @Test
    void hashIsKeyed() {
        // The point of HMAC over bare SHA-256: without the deployment key an
        // attacker cannot precompute the million-value code space.
        OtpHasher a = new OtpHasher(KEY);
        OtpHasher b = new OtpHasher("another-otp-hmac-secret-at-least-32-bytes!!");
        assertThat(a.hash("123456")).isNotEqualTo(b.hash("123456"));
    }

    @Test
    void trimsKeyWhitespace() {
        assertThat(new OtpHasher("  " + KEY + "\n").hash("123456"))
                .isEqualTo(new OtpHasher(KEY).hash("123456"));
    }

    @Test
    void refusesMissingOrShortKey() {
        assertThatIllegalStateException().isThrownBy(() -> new OtpHasher(null))
                .withMessageContaining("OTP_HMAC_SECRET");
        assertThatIllegalStateException().isThrownBy(() -> new OtpHasher(""));
        assertThatIllegalStateException().isThrownBy(() -> new OtpHasher("too-short"));
    }
}
