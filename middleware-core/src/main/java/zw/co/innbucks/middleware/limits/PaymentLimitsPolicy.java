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
 * @param overrides every value that differs from the file — set by an
 *                  operator, or derived from one (see {@link AppliedOverride#derivedFrom})
 *                  — for logs and audit
 * @param warnings  higher-tier-stricter inversions the overrides left in the
 *                  effective policy: logged and audited, never a boot refusal
 */
public record PaymentLimitsPolicy(
        Country country,
        String currency,
        String source,
        String sha256,
        Map<KycTier, TierLimits> tiers,
        List<AppliedOverride> overrides,
        List<String> warnings
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
        warnings = List.copyOf(warnings);
    }

    /** A policy with nothing to warn about — the file as reviewed, or tests. */
    public PaymentLimitsPolicy(Country country, String currency, String source, String sha256,
                               Map<KycTier, TierLimits> tiers, List<AppliedOverride> overrides) {
        this(country, currency, source, sha256, tiers, overrides, List.of());
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
     * One effective value that differs from the file because of the emergency
     * overrides: either set by the operator, or DERIVED — lowered to a tighter
     * period limit that an override set, so {@code per_payment <= daily <=
     * monthly} still holds within the tier.
     *
     * @param fileValue   the packaged value it replaced; {@code null} when the file had none (unlimited)
     * @param derivedFrom {@code null} when the operator set this value; otherwise
     *                    the kind of the same tier whose effective value it was
     *                    lowered to (itself overridden, or lowered in turn)
     */
    public record AppliedOverride(KycTier tier, LimitKind kind, Long fileValue, long overrideValue,
                                  LimitKind derivedFrom) {

        /** An override the operator set. */
        public AppliedOverride(KycTier tier, LimitKind kind, Long fileValue, long overrideValue) {
            this(tier, kind, fileValue, overrideValue, null);
        }

        public boolean derived() {
            return derivedFrom != null;
        }

        public String describe() {
            return tier.dbValue() + "." + kind.wireName() + " = " + overrideValue
                    + " (policy file: " + (fileValue == null ? "none" : fileValue)
                    + (derivedFrom == null ? "" : "; lowered to this tier's effective " + derivedFrom.wireName()
                            + " so per_payment <= daily <= monthly holds")
                    + ")";
        }
    }
}
