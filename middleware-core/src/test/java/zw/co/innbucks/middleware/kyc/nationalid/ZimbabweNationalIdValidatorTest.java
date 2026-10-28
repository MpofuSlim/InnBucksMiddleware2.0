package zw.co.innbucks.middleware.kyc.nationalid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Synthetic numbers only — never a real ID in the repo. */
class ZimbabweNationalIdValidatorTest {

    private static final char EN_DASH = (char) 0x2013;
    private static final char NO_BREAK_SPACE = (char) 0x00A0;
    private static final char MINUS_SIGN = (char) 0x2212;

    private final ZimbabweNationalIdValidator validator = new ZimbabweNationalIdValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "63-1234567 X 18", "63-1234567X18", "631234567X18", "63 1234567 x 18", "63-1234567-x-18",
            " 63 - 1234567 - X - 18 ", "63\t1234567\tX\t18"})
    void everyCommonSpellingCanonicalisesToOneForm(String typed) {
        assertThat(validator.canonicalise(typed)).isEqualTo("631234567X18");
    }

    @Test
    void unicodeDashesAndSpacesAreFormattingToo() {
        assertThat(validator.canonicalise("63" + EN_DASH + "1234567" + NO_BREAK_SPACE + "X" + MINUS_SIGN + "18"))
                .isEqualTo("631234567X18");
    }

    /**
     * Separators are formatting, so where the customer put the dash cannot be
     * checked: "6-1234567" reads as district 61, serial 234567. The reviewer
     * holding the card photo is the check for that.
     */
    @Test
    void aSixDigitSerialIsValid() {
        assertThat(validator.canonicalise("08-123456 D 45")).isEqualTo("08123456D45");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "63-12345 X 18",        // serial too short
            "63-12345678 X 18",     // serial too long
            "63-1234567 18",        // no check letter
            "63-1234567 XY 18",     // two letters
            "6-123456 X 18",        // 7 digits before the letter: too few for district + serial
            "63-1234567 X 1",       // origin too short
            "X3-1234567 X 18",      // letter where a digit goes
            "63/1234567/X/18",      // a separator we do not accept
            "63.1234567.X.18",
            "63-1234567 X 18!",
            "",
            "   "})
    void malformedNumbersAreRefused(String typed) {
        assertThatThrownBy(() -> validator.canonicalise(typed)).isInstanceOf(InvalidNationalIdException.class);
    }

    @Test
    void aNonAsciiLetterIsRefused_notFoldedIntoAscii() {
        // the dotless i upper-cases to a plain I in Java; it must not sneak in as one
        assertThatThrownBy(() -> validator.canonicalise("63-1234567 " + (char) 0x0131 + " 18"))
                .isInstanceOf(InvalidNationalIdException.class);
    }

    @Test
    void theRefusalNeverRepeatsTheNumber() {
        assertThatThrownBy(() -> validator.canonicalise("63-99999 Q 77"))
                .isInstanceOf(InvalidNationalIdException.class)
                .hasMessageNotContaining("99999");
    }

    @Test
    void commonSpellingsCoverHowPeopleTypeIt() {
        Set<String> spellings = validator.commonSpellings("631234567X18");

        assertThat(spellings).hasSize(54).contains(
                "631234567X18", "631234567x18", "63-1234567 X 18", "63-1234567-X-18", "63-1234567X18",
                "63-1234567 x 18", "63 1234567 X 18", "631234567 X 18", "63-1234567X-18");
    }
}
