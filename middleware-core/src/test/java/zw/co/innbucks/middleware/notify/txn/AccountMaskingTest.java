package zw.co.innbucks.middleware.notify.txn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMaskingTest {

    @Test
    void masksTheWalletExternalIdToItsLastFourIdentifyingCharacters() {
        assertThat(AccountMasking.maskAccount("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet"))
                .isEqualTo("6e7f");
    }

    @Test
    void dropsTheSlotSuffixFirst() {
        // Masking the tail of ":wallet" would print "llet" for every customer
        // in the cell — identifying nothing, which defeats the point.
        assertThat(AccountMasking.maskAccount("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f:wallet"))
                .isNotEqualTo("llet");
    }

    @Test
    void ignoresSeparatorsSoHyphenationCannotShiftTheTail() {
        assertThat(AccountMasking.maskAccount("0000-1111-2222-3333"))
                .isEqualTo(AccountMasking.maskAccount("0000111122223333"));
    }

    @Test
    void shortReferencesPassThroughWhole() {
        assertThat(AccountMasking.maskAccount("42")).isEqualTo("42");
    }

    @Test
    void missingOrUnusableReferencesRenderAsPlaceholderNeverAnException() {
        assertThat(AccountMasking.maskAccount(null)).isEqualTo("----");
        assertThat(AccountMasking.maskAccount("  ")).isEqualTo("----");
        assertThat(AccountMasking.maskAccount(":wallet")).isEqualTo("----");
        assertThat(AccountMasking.maskAccount("---")).isEqualTo("----");
    }
}
