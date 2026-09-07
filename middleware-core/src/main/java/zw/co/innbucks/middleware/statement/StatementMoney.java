package zw.co.innbucks.middleware.statement;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Currency;
import java.util.Locale;

/**
 * Money rendering for statement output, mirroring the SMS composer's style
 * ({@code 1,234.56} — grouped, scaled to the currency's minor digits) but
 * accepting SIGNED minor units, because a statement renders balances and a
 * balance can be negative. Amounts on the port stay non-negative; this class
 * is display only.
 */
public final class StatementMoney {

    private StatementMoney() {
    }

    public static String format(long signedMinor, String currencyCode) {
        int scale = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        BigDecimal major = BigDecimal.valueOf(signedMinor).movePointLeft(scale);
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setMinimumFractionDigits(Math.max(scale, 0));
        format.setMaximumFractionDigits(Math.max(scale, 0));
        return format.format(major);
    }
}
