package zw.co.innbucks.middleware.common.msisdn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KenyaMsisdnNormalizerTest {

    private final KenyaMsisdnNormalizer normalizer = new KenyaMsisdnNormalizer();

    @ParameterizedTest
    @CsvSource({
            "0712345678,    +254712345678",
            "+254712345678, +254712345678",
            "254712345678,  +254712345678",
            "+254 712 345 678, +254712345678",
            "0110000000, +254110000000",
            "+254110000000, +254110000000"
    })
    void normalisesAllAcceptedKenyanFormats(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "0612345678",            // wrong mobile prefix (not 7 or 1)
            "0812345678",            // wrong mobile prefix
            "+25471234567",          // too short
            "+2547123456789",        // too long
            "+255712345678",         // Tanzanian code, not Kenyan
            "0712-345-678a",         // contains letter
            "abc"
    })
    void rejectsInvalidInputs(String input) {
        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOf(InvalidMsisdnException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(InvalidMsisdnException.class);
    }

    @Test
    void isValidReturnsBooleanWithoutThrowing() {
        assertThat(normalizer.isValid("0712345678")).isTrue();
        assertThat(normalizer.isValid("nonsense")).isFalse();
    }
}
