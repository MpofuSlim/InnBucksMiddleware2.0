package zw.co.innbucks.middleware.kyc.nationalid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.kyc.SignupMatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sign-up hash was taken over the number AS TYPED (RegisterService:
 * {@code hash(nationalId.trim())}). Matching it means trying the spellings.
 */
class KycNationalIdsTest {

    private final NationalIdHasher hasher = new NationalIdHasher("test-national-id-hmac-key-at-least-32-bytes-long");
    private final KycNationalIds ids = new KycNationalIds(new ZimbabweNationalIdValidator(), hasher);

    /** What RegisterService stored for a number typed this way. */
    private String signupHashOf(String typed) {
        return hasher.hash(typed.trim());
    }

    @ParameterizedTest
    @ValueSource(strings = {"63-1234567 X 18", "631234567x18", "63-1234567-X-18", "63 1234567 x 18 ", "63-1234567X18"})
    void aCommonSpellingAtSignUpIsAMatch(String typedAtSignUp) {
        String canonical = ids.canonicalise("631234567X18").value();
        assertThat(ids.signupMatch(signupHashOf(typedAtSignUp), canonical)).isEqualTo(SignupMatch.MATCH);
    }

    @Test
    void aDifferentNumberIsNoMatch() {
        String canonical = ids.canonicalise("63-1234567 X 18").value();
        assertThat(ids.signupMatch(signupHashOf("63-7654321 X 18"), canonical)).isEqualTo(SignupMatch.NO_MATCH);
    }

    @Test
    void anUncommonSeparatorAtSignUpReadsAsNoMatch_whichTheReviewerIsToldCanStillBeTheSameNumber() {
        String canonical = ids.canonicalise("63-1234567 X 18").value();
        assertThat(ids.signupMatch(signupHashOf("63/1234567/X/18"), canonical)).isEqualTo(SignupMatch.NO_MATCH);
    }

    @Test
    void noNumberAtSignUpIsNoneGiven() {
        assertThat(ids.signupMatch(null, "631234567X18")).isEqualTo(SignupMatch.NONE_GIVEN);
        assertThat(ids.signupMatch(" ", "631234567X18")).isEqualTo(SignupMatch.NONE_GIVEN);
    }

    @Test
    void canonicalCarriesTheHmacOfTheCanonicalForm_andPrintsOnlyTheMask() {
        KycNationalIds.Canonical canonical = ids.canonicalise("63-1234567 x 18");

        assertThat(canonical.value()).isEqualTo("631234567X18");
        assertThat(canonical.hmac()).isEqualTo(hasher.hash("631234567X18"));
        assertThat(canonical.toString()).doesNotContain("1234567").contains(canonical.masked());
        assertThat(ids.spellingHashes("631234567X18")).contains(canonical.hmac()).hasSize(54);
    }
}
