package zw.co.innbucks.middleware.limits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import zw.co.innbucks.middleware.customer.KycTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * The ONLY runtime configuration of the payment limits: EMERGENCY,
 * TIGHTEN-ONLY overrides ({@code innbucks.limits.override.<tier>.<kind>},
 * env {@code LIMITS_OVERRIDE_<TIER>_<KIND>}).
 *
 * <p>The limit VALUES live in the reviewed policy file packaged into the
 * image ({@code limits/limits-<iso>.yaml}) — never here. An override exists
 * so an operator can clamp a tier down within minutes (fraud wave, a
 * compliance instruction) without a release; it can never loosen, because an
 * unreviewed {@code .env} value that loosens limits is exactly the failure
 * the policy file exists to prevent. {@link PaymentLimitsPolicyLoader} refuses
 * boot on a looser override, and logs + audits every active one.
 *
 * <p>Blank / unset = no override. All values must be &gt; 0.
 */
@ConfigurationProperties(prefix = "innbucks.limits")
public record PaymentLimitsProperties(Overrides override) {

    /** No overrides at all (unit tests; a cell that never sets one). */
    public static PaymentLimitsProperties none() {
        return new PaymentLimitsProperties(null);
    }

    /** Per-tier overrides; a null tier means "none for that tier". */
    public record Overrides(TierOverride basic, TierOverride standard, TierOverride enhanced) {
    }

    /**
     * Overrides for one tier, in the policy file's units (minor units; count).
     * Field names bind as {@code per-payment}, {@code daily}, {@code monthly},
     * {@code daily-count}.
     */
    public record TierOverride(Long perPayment, Long daily, Long monthly, Long dailyCount) {

        Long get(LimitKind kind) {
            return switch (kind) {
                case PER_PAYMENT -> perPayment;
                case DAILY -> daily;
                case MONTHLY -> monthly;
                case DAILY_COUNT -> dailyCount;
            };
        }
    }

    /** The overrides as a tier -> (kind -> value) map, absent entries omitted. */
    public Map<KycTier, Map<LimitKind, Long>> asMap() {
        Map<KycTier, Map<LimitKind, Long>> out = new EnumMap<>(KycTier.class);
        if (override == null) {
            return out;
        }
        put(out, KycTier.BASIC, override.basic());
        put(out, KycTier.STANDARD, override.standard());
        put(out, KycTier.ENHANCED, override.enhanced());
        return out;
    }

    private static void put(Map<KycTier, Map<LimitKind, Long>> out, KycTier tier, TierOverride values) {
        if (values == null) {
            return;
        }
        Map<LimitKind, Long> kinds = new EnumMap<>(LimitKind.class);
        for (LimitKind kind : LimitKind.values()) {
            Long value = values.get(kind);
            if (value != null) {
                kinds.put(kind, value);
            }
        }
        if (!kinds.isEmpty()) {
            out.put(tier, kinds);
        }
    }
}
