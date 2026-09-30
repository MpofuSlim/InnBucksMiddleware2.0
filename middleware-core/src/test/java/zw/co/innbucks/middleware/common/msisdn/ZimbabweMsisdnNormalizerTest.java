package zw.co.innbucks.middleware.common.msisdn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zw.co.innbucks.middleware.common.country.Country;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZimbabweMsisdnNormalizerTest {

    private final ZimbabweMsisdnNormalizer normalizer = new ZimbabweMsisdnNormalizer();

    @Test
    void servesZimbabwe() {
        assertThat(normalizer.country()).isEqualTo(Country.ZW);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+263771234567",     // already canonical
            "263771234567",      // international, no plus
            "0771234567",        // national
            "077 123 4567",      // spaces
            "077-123-4567",      // dashes
            "(077) 123.4567"     // parens and dots
    })
    void normalisesEveryShapeCustomersType(String input) {
        assertThat(normalizer.normalize(input)).isEqualTo("+263771234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0711234567",   // NetOne
            "0731234567",   // Telecel
            "0771234567",   // Econet
            "0781234567",   // Econet
            "0791234567",   // Econet (added 2026-09-30)
            "+263791234567" // Econet, canonical
    })
    void acceptsEveryMobileNetworkPrefix(String input) {
        assertThat(normalizer.isValid(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0242123456",     // Harare landline — we only ever SMS mobiles
            "0721234567",     // 72 is not an allocated mobile prefix
            "0761234567",     // 76 likewise
            "077123456",      // one digit short
            "07712345678",    // one digit long
            "+254771234567",  // Kenyan number in a Zimbabwe cell
            "+263771234567 ext 4"
    })
    void rejectsNonMobileWrongLengthAndForeignNumbers(String input) {
        assertThat(normalizer.isValid(input)).isFalse();
    }

    @Test
    void normalisesAnEconet079NumberToE164() {
        assertThat(normalizer.normalize("079 123 4567")).isEqualTo("+263791234567");
        assertThat(normalizer.normalize("263791234567")).isEqualTo("+263791234567");
    }

    @Test
    void rejectsLettersRatherThanSilentlyStrippingThem() {
        assertThatThrownBy(() -> normalizer.normalize("077ABC4567"))
                .isInstanceOf(InvalidMsisdnException.class)
                .hasMessageContaining("not digits or formatting");
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(InvalidMsisdnException.class);
        assertThatThrownBy(() -> normalizer.normalize("   "))
                .isInstanceOf(InvalidMsisdnException.class);
    }
}
