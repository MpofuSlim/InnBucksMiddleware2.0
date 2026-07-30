package zw.co.innbucks.middleware.corebanking.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MinorUnitsTest {

    @Test
    void convertsMajorToMinorExactly() {
        assertThat(MinorUnits.ofMajor(new BigDecimal("12.34"), "KES"))
                .isEqualTo(new MinorUnits(1234L, "KES"));
        assertThat(MinorUnits.ofMajor(new BigDecimal("0.01"), "USD"))
                .isEqualTo(new MinorUnits(1L, "USD"));
        assertThat(MinorUnits.ofMajor(BigDecimal.ZERO, "KES"))
                .isEqualTo(new MinorUnits(0L, "KES"));
    }

    @Test
    void roundTripsBackToMajor() {
        assertThat(new MinorUnits(1234L, "KES").toMajor()).isEqualByComparingTo("12.34");
        assertThat(new MinorUnits(5L, "USD").toMajor()).isEqualByComparingTo("0.05");
    }

    @Test
    void rejectsSubMinorPrecision() {
        // 12.345 KES cannot be represented in cents — silently rounding here is
        // exactly the class of bug the single-conversion-point rule exists to stop.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MinorUnits.ofMajor(new BigDecimal("12.345"), "KES"))
                .withMessageContaining("sub-minor-unit");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MinorUnits(-1L, "KES"));
    }

    @Test
    void rejectsUnknownCurrency() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinorUnits(100L, "NOT_A_CURRENCY"));
    }

    @Test
    void zeroDecimalCurrenciesUseWholeUnits() {
        // JPY has 0 fraction digits: 1234 JPY == 1234 minor units.
        assertThat(MinorUnits.ofMajor(new BigDecimal("1234"), "JPY"))
                .isEqualTo(new MinorUnits(1234L, "JPY"));
        assertThat(new MinorUnits(1234L, "JPY").toMajor()).isEqualByComparingTo("1234");
    }
}
