package zw.co.innbucks.middleware.limits;

import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.customer.KycTier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The per-tier payment limits in force for this cell: the packaged policy
 * file with any tighten-only emergency overrides already applied. Immutable,
 * built once at boot by {@link PaymentLimitsPolicyLoader}; every limit check
 * and {@code GET /me/limits} read the same instance, so the screen and the
 * refusal cannot disagree about the rules.
 *
 * @param country   the cell's market (the file states it; boot refuses a mismatch)
 * @param currency  the cell currency the money values are in (boot refuses a mismatch)
 * @param source    the classpath resource the values came from, for logs and audit
 * @param sha256    hex SHA-256 of the file's exact bytes — "which limits were in force"
 * @param tiers     EFFECTIVE limits per tier (file, then overrides)
 * @param overrides the overrides that actually changed a value, for logs and audit
 */
public record PaymentLimitsPolicy(
        Country country,
        String currency,
        String source,
        String sha256,
        Map<KycTier, TierLimits> tiers,
        List<AppliedOverride> overrides
) {

    public PaymentLimitsPolicy {
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sha256, "sha256");
        EnumMap<KycTier, TierLimits> copy = new EnumMap<>(KycTier.class);
        for (KycTier tier : KycTier.values()) {
            copy.put(tier, Objects.requireNonNull(tiers.get(tier), "tiers." + tier));
        }
        tiers = Collections.unmodifiableMap(copy);
        overrides = List.copyOf(overrides);
    }

    /** Effective limits for a tier; a null tier gets BASIC's (the tightest). */
    public TierLimits limitsFor(KycTier tier) {
        return tiers.get(tier == null ? KycTier.BASIC : tier);
    }

    /** True when no tier has any limit configured — the shipped state until compliance supplies values. */
    public boolean isDormant() {
        return tiers.values().stream().allMatch(TierLimits::isDormant);
    }

    /**
     * One emergency override that tightened (or introduced) a value.
     *
     * @param fileValue the packaged value it replaced; {@code null} when the file had none (unlimited)
     */
    public record AppliedOverride(KycTier tier, LimitKind kind, Long fileValue, long overrideValue) {

        public String describe() {
            return tier.dbValue() + "." + kind.wireName() + " = " + overrideValue
                    + " (policy file: " + (fileValue == null ? "none" : fileValue) + ")";
        }
    }
}
