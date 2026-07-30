package zw.co.innbucks.middleware.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static zw.co.innbucks.middleware.notify.SmsTextSanitizer.toGsmSafe;

/**
 * Pins the transliteration rules ported from the ticketing fleet, whose
 * whitelist was established by probing the live SMS gateway character by
 * character. If the gateway's accepted set ever changes, update BOTH repos.
 */
class SmsTextSanitizerTest {

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(toGsmSafe(null)).isNull();
        assertThat(toGsmSafe("")).isEmpty();
    }

    @Test
    void plainGsmSafeTextIsUntouched() {
        String text = "Your Innbucks PIN setup code is 123456. Do not share it with anyone.";
        assertThat(toGsmSafe(text)).isEqualTo(text);
    }

    @Test
    void typographicPunctuationTransliteratesToAscii() {
        assertThat(toGsmSafe("Approved — enjoy")).isEqualTo("Approved - enjoy");
        assertThat(toGsmSafe("don’t")).isEqualTo("don't");
        assertThat(toGsmSafe("“quoted”")).isEqualTo("'quoted'");
        assertThat(toGsmSafe("wait…")).isEqualTo("wait...");
        assertThat(toGsmSafe("a•b")).isEqualTo("a-b");
    }

    @Test
    void gatewayRejectedAsciiCharactersAreSubstituted() {
        // ! : / ? " * ; are 400-rejected by the gateway despite being GSM-7.
        assertThat(toGsmSafe("Done! Ready? Yes; ok")).isEqualTo("Done. Ready. Yes. ok");
        assertThat(toGsmSafe("code: 12*34/56")).isEqualTo("code 12 34 56");
        assertThat(toGsmSafe("a\"b")).isEqualTo("a'b");
    }

    @Test
    void diacriticsDegradeToBaseLetters() {
        assertThat(toGsmSafe("café résumé")).isEqualTo("cafe resume");
    }

    @Test
    void anythingOutsideTheWhitelistCollapsesToOneSpace() {
        // Supplementary chars (emoji) must collapse to ONE space, not one per
        // UTF-16 surrogate half; runs of spaces collapse afterwards.
        assertThat(toGsmSafe("hi 👍 there")).isEqualTo("hi there");
        assertThat(toGsmSafe("a£b")).isEqualTo("a b");
    }

    @Test
    void acceptedPunctuationSurvives() {
        String accepted = "ok .,()-%@&#'+ 09AZaz";
        assertThat(toGsmSafe(accepted)).isEqualTo(accepted);
    }
}
