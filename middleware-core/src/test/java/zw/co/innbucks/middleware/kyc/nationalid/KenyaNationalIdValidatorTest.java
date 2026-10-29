package zw.co.innbucks.middleware.kyc.nationalid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KenyaNationalIdValidatorTest {

    private final KenyaNationalIdValidator validator = new KenyaNationalIdValidator();

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "1234 5678", "1234-5678", " 12345678 "})
    void digitsWithFormattingCanonicalise(String typed) {
        assertThat(validator.canonicalise(typed)).isEqualTo("12345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "123456789", "0123456"})
    void anyLengthFromFiveToNineDigitsIsAccepted_leadingZerosKept(String typed) {
        assertThat(validator.canonicalise(typed)).isEqualTo(typed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234", "1234567890", "1234567A", "A1234567", "12.345.678", ""})
    void anythingElseIsRefused(String typed) {
        assertThatThrownBy(() -> validator.canonicalise(typed)).isInstanceOf(InvalidNationalIdException.class);
    }

    @Test
    void theOnlySpellingIsTheDigits() {
        assertThat(validator.commonSpellings("12345678")).containsExactly("12345678");
    }
}
