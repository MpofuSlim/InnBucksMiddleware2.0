package zw.co.innbucks.middleware.auth.pin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PinHasherTest {

    private final PinHasher hasher = new PinHasher();

    @Test
    void hashesAndVerifiesCorrectPin() {
        String hash = hasher.hash("1234");
        assertThat(hasher.matches("1234", hash)).isTrue();
    }

    @Test
    void rejectsWrongPin() {
        String hash = hasher.hash("1234");
        assertThat(hasher.matches("9999", hash)).isFalse();
    }

    @Test
    void producesArgon2idHashFormat() {
        String hash = hasher.hash("1234");
        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void differentInvocationsProduceDifferentSalts() {
        String first = hasher.hash("1234");
        String second = hasher.hash("1234");
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("1234", first)).isTrue();
        assertThat(hasher.matches("1234", second)).isTrue();
    }
}
