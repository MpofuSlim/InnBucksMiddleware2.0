package zw.co.innbucks.middleware.statement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementMoneyTest {

    @Test
    void groupsAndScalesToTheCurrency() {
        assertThat(StatementMoney.format(123_456, "USD")).isEqualTo("1,234.56");
        assertThat(StatementMoney.format(0, "USD")).isEqualTo("0.00");
    }

    @Test
    void negativeBalancesRenderSigned() {
        assertThat(StatementMoney.format(-50, "USD")).isEqualTo("-0.50");
        assertThat(StatementMoney.format(-1_234_500, "USD")).isEqualTo("-12,345.00");
    }

    @Test
    void zeroDecimalCurrenciesGetNoFraction() {
        assertThat(StatementMoney.format(1_000_000, "JPY")).isEqualTo("1,000,000");
    }
}
