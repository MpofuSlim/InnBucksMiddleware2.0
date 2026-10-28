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
     * Lenient parse: trims, ignores case, and answers {@link Optional#empty()}
     * for null, blank or unrecognised values instead of throwing. The ONLY
     * parser: the strict, throwing {@code fromDbValue} (and
     * {@code Customer.kycTierEnum()} over it) went when its last caller, the
     * payment path, moved to {@code KycTierResolver} — one garbage value in a
     * customer row turned every payment of that customer into a 500. Login and
     * {@code /me/profile} carry the stored string as is. Resolve a tier for a
     * decision through {@code KycTierResolver}, never by parsing here.
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
