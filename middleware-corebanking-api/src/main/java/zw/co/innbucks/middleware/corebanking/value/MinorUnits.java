package zw.co.innbucks.middleware.corebanking.value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * A money amount in the currency's minor units (cents for KES/USD). This is
 * the ONLY representation that crosses the {@code CoreBankingPort}: exactly
 * one major↔minor conversion point exists per adapter, and each adapter must
 * cross-check any amount the core echoes back — the shape of the proven
 * 100x-charge guard.
 *
 * <p>{@code amount} must be positive: movements and balances at the port are
 * directional by method, never by sign.
 */
public record MinorUnits(long amount, String currencyCode) {

    public MinorUnits {
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (amount < 0) {
            throw new IllegalArgumentException("MinorUnits amount must be >= 0, got " + amount);
        }
        // Validates the ISO 4217 code and pins the scale used by toMajor/ofMajor.
        Currency.getInstance(currencyCode);
    }

    /** Convert a major-units decimal (e.g. "12.34" KES) exactly; rejects sub-minor precision. */
    public static MinorUnits ofMajor(BigDecimal major, String currencyCode) {
        Objects.requireNonNull(major, "major");
        int scale = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        BigDecimal scaled;
        try {
            scaled = major.movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "Amount " + major + " " + currencyCode + " has sub-minor-unit precision", ex);
        }
        return new MinorUnits(scaled.longValueExact(), currencyCode);
    }

    public BigDecimal toMajor() {
        int scale = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return BigDecimal.valueOf(amount).movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY);
    }
}
