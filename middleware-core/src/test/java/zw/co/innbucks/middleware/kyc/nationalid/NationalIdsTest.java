package zw.co.innbucks.middleware.kyc.nationalid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NationalIdsTest {

    private static final String BULLET = String.valueOf((char) 0x2022);

    @Test
    void aZimbabweanNumberShowsItsFirstTwoAndLastThree() {
        assertThat(NationalIds.mask("631234567X18")).isEqualTo("63" + BULLET.repeat(7) + "X18");
        assertThat(NationalIds.mask("08123456D45")).isEqualTo("08" + BULLET.repeat(6) + "D45");
    }

    @Test
    void aShortNumberShowsOnlyItsLastThree_soMostOfItStaysHidden() {
        // first2 + last3 of 8 digits would show 5 of 8
        assertThat(NationalIds.mask("12345678")).isEqualTo(BULLET.repeat(5) + "678");
        assertThat(NationalIds.mask("12345")).isEqualTo(BULLET.repeat(2) + "345");
        assertThat(NationalIds.mask("123")).isEqualTo(BULLET.repeat(3));
    }

    @Test
    void overlongInputIsRefusedBeforeAnythingElse() {
        assertThatThrownBy(() -> NationalIds.stripSeparatorsAndUpperCase("6".repeat(41)))
                .isInstanceOf(InvalidNationalIdException.class).hasMessageContaining("too long");
    }

    @Test
    void onlySeparatorsIsNoNumber() {
        assertThatThrownBy(() -> NationalIds.stripSeparatorsAndUpperCase(" - - "))
                .isInstanceOf(InvalidNationalIdException.class).hasMessageContaining("required");
    }
}
