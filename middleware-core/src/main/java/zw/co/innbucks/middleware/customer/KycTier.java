package zw.co.innbucks.middleware.customer;

import java.util.Locale;
import java.util.Optional;

/**
 * KYC tiers, LOWEST first — declaration order is the upgrade order, which the
 * payment-limit policy relies on ("a higher tier is never stricter").
 */
public enum KycTier {
    BASIC,
    STANDARD,
    ENHANCED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Strict parse — THROWS on anything that is not a tier name. Kept for the
     * non-money callers that always had it; the money path never uses it (see
     * {@link #parse} and {@code KycTierResolver}), because one garbage value in
     * a customer row must not turn every payment of that customer into a 500.
     */
    public static KycTier fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Lenient parse: trims, ignores case, and answers {@link Optional#empty()}
     * for null, blank or unrecognised values instead of throwing.
     */
    public static Optional<KycTier> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        for (KycTier tier : values()) {
            if (tier.name().equals(normalised)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /** The tier above this one; {@code null} at the top. */
    public KycTier next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }
}
