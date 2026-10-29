package zw.co.innbucks.middleware.limits;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.limits.PaymentLimitsPolicy.AppliedOverride;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates a per-country payment-limits policy file, then applies
 * the tighten-only emergency overrides. Pure — no Spring, no I/O beyond the
 * bytes handed in — so every refusal rule is unit-tested without a context.
 *
 * <p>The file is a reviewed artefact and is parsed STRICTLY: an unknown key,
 * a duplicate key, a quoted number, a decimal, a null value or a missing tier
 * is a boot refusal, never a silently ignored line. A typo that dropped a
 * limit would otherwise read as "absent = unlimited" — the most expensive
 * possible reading of a mistake.
 *
 * <p>Rules (each failure throws {@link PaymentLimitsPolicyException}):
 * <ul>
 *   <li>{@code country} equals the cell's {@code INNBUCKS_COUNTRY};
 *       {@code currency} equals the cell currency.</li>
 *   <li>Every tier is present; each value is an integer &gt; 0.</li>
 *   <li>{@code per_payment <= daily <= monthly} wherever both sides are present.</li>
 *   <li>A higher tier is never stricter than a lower one for the same kind.
 *       Absent = unlimited, so once a lower tier is unlimited for a kind every
 *       higher tier must be unlimited for it too.</li>
 *   <li>An override must be &gt; 0 and no looser than the file's value (any
 *       value tightens an absent one). That is the ONLY test an override
 *       faces: a value that tightens is never refused.</li>
 * </ul>
 *
 * <p>The two consistency rules bind the FILE, which is a reviewed artefact.
 * They do not refuse the effective (overridden) policy, because the override
 * is the emergency lever, and a boot refusal at the moment someone clamps a
 * limit takes the whole cell down — login and statements included — for being
 * too cautious. Instead:
 * <ul>
 *   <li><b>Within a tier, the ordering is restored by LOWERING</b>, never by
 *       refusing: a present daily above an overridden monthly becomes the
 *       monthly, and a present per_payment above the (possibly lowered) daily or
 *       the monthly becomes the smaller of them. Each lowered value is recorded
 *       as a {@linkplain AppliedOverride#derived() derived} override, so it is
 *       logged and audited like the operator's own. This changes no payment's
 *       outcome — a payment above the daily is refused by the daily check
 *       whatever per_payment says, and a day lies inside its month, so the
 *       month's usage is never below the day's — only which limit a refusal
 *       names, and the numbers {@code GET /me/limits} shows, which become true.</li>
 *   <li><b>A higher tier left stricter than a lower one is a WARNING</b>
 *       ({@link PaymentLimitsPolicy#warnings()}), never a refusal and never a
 *       derived change: each tier is enforced on its own, so the only effect is
 *       that upgrading lowers that limit while the override stands. Lowering the
 *       lower tiers to match would tighten customers the operator did not
 *       name.</li>
 * </ul>
 */
public final class PaymentLimitsPolicyLoader {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("country", "currency", "tiers");

    private PaymentLimitsPolicyLoader() {
    }

    /** Where a country's policy lives on the classpath, e.g. {@code limits/limits-zw.yaml}. */
    public static String resourcePathFor(Country country) {
        return "limits/limits-" + country.name().toLowerCase(Locale.ROOT) + ".yaml";
    }

    /**
     * @param source       the resource path, used in every message
     * @param bytes        the file's exact bytes (the digest is over these)
     * @param cellCountry  {@code INNBUCKS_COUNTRY}
     * @param cellCurrency the core's cell currency ({@code CoreBankingPort#cellCurrency})
     * @param overrides    tighten-only overrides, tier -> kind -> value
     */
    public static PaymentLimitsPolicy load(String source, byte[] bytes, Country cellCountry,
                                           String cellCurrency,
                                           Map<KycTier, Map<LimitKind, Long>> overrides) {
        ParsedFile file = parse(source, bytes);
        if (file.country() != cellCountry) {
            throw fail(source, "states country " + file.country() + " but this cell is "
                    + cellCountry + " — wrong file packaged or INNBUCKS_COUNTRY mis-set");
        }
        if (cellCurrency == null || !file.currency().equals(cellCurrency)) {
            throw fail(source, "states currency " + file.currency() + " but the cell currency is "
                    + cellCurrency + " — the limits would be compared against the wrong money");
        }
        validate(source, file.tiers());

        Map<KycTier, TierLimits> effective = new EnumMap<>(file.tiers());
        Map<KycTier, Map<LimitKind, AppliedOverride>> applied = new EnumMap<>(KycTier.class);
        overrides.forEach((tier, kinds) -> kinds.forEach((kind, value) -> {
            String name = "innbucks.limits.override." + tier.dbValue() + "." + propertyName(kind);
            if (value == null || value <= 0) {
                throw new PaymentLimitsPolicyException(name + "=" + value
                        + " is not a positive value — unset it, or give a value > 0");
            }
            if (kind == LimitKind.DAILY_COUNT && value > Integer.MAX_VALUE) {
                throw new PaymentLimitsPolicyException(name + "=" + value + " is not a sane payment count");
            }
            Long fileValue = file.tiers().get(tier).get(kind);
            if (fileValue != null && value > fileValue) {
                throw new PaymentLimitsPolicyException(name + "=" + value + " is LOOSER than "
                        + source + "'s " + tier.dbValue() + "." + kind.wireName() + "=" + fileValue
                        + ". An override may only TIGHTEN a limit; loosening one needs a reviewed "
                        + "change to the policy file.");
            }
            if (fileValue == null || value < fileValue) {
                effective.put(tier, effective.get(tier).with(kind, value));
                applied.computeIfAbsent(tier, t -> new EnumMap<>(LimitKind.class))
                        .put(kind, new AppliedOverride(tier, kind, fileValue, value));
            }
        }));
        List<String> warnings = List.of();
        if (!applied.isEmpty()) {
            for (KycTier tier : KycTier.values()) {
                restoreOrdering(tier, file.tiers().get(tier), effective, applied);
            }
            warnings = tierInversions(effective).stream()
                    .map(inversion -> inversion.describe(source + " with the emergency overrides applied")
                            + ". NOT refused: each tier is enforced on its own, so while the override stands "
                            + "upgrading to '" + inversion.higher().dbValue() + "' lowers this limit. Override "
                            + "the lower tier(s) too if that was not the intent.")
                    .toList();
        }
        List<AppliedOverride> recorded = new ArrayList<>();
        applied.values().forEach(byKind -> recorded.addAll(byKind.values()));
        return new PaymentLimitsPolicy(cellCountry, file.currency(), source, sha256(bytes),
                effective, recorded, warnings);
    }

    /**
     * Lowers a present daily above the monthly, then a present per_payment
     * above the daily or monthly, in one tier of the effective policy. Only
     * PRESENT values are lowered: an absent per_payment stays absent (the daily
     * already bounds a single payment, and inventing a kind would list a limit
     * the file never had). Since the file itself is ordered, anything lowered
     * here was put out of order by an override.
     */
    private static void restoreOrdering(KycTier tier, TierLimits fileLimits, Map<KycTier, TierLimits> effective,
                                        Map<KycTier, Map<LimitKind, AppliedOverride>> applied) {
        TierLimits t = effective.get(tier);
        if (t.dailyMinor() != null && t.monthlyMinor() != null && t.dailyMinor() > t.monthlyMinor()) {
            t = lower(tier, t, LimitKind.DAILY, t.monthlyMinor(), LimitKind.MONTHLY, fileLimits, applied);
        }
        if (t.perPaymentMinor() != null) {
            LimitKind ceilingKind = null;
            long ceiling = t.perPaymentMinor();
            if (t.dailyMinor() != null && t.dailyMinor() < ceiling) {
                ceilingKind = LimitKind.DAILY;
                ceiling = t.dailyMinor();
            }
            if (t.monthlyMinor() != null && t.monthlyMinor() < ceiling) {
                ceilingKind = LimitKind.MONTHLY;
                ceiling = t.monthlyMinor();
            }
            if (ceilingKind != null) {
                t = lower(tier, t, LimitKind.PER_PAYMENT, ceiling, ceilingKind, fileLimits, applied);
            }
        }
        effective.put(tier, t);
    }

    private static TierLimits lower(KycTier tier, TierLimits limits, LimitKind kind, long value,
                                    LimitKind derivedFrom, TierLimits fileLimits,
                                    Map<KycTier, Map<LimitKind, AppliedOverride>> applied) {
        // Replaces an operator entry for the same kind: one record per value,
        // carrying the value actually enforced.
        applied.computeIfAbsent(tier, t -> new EnumMap<>(LimitKind.class))
                .put(kind, new AppliedOverride(tier, kind, fileLimits.get(kind), value, derivedFrom));
        return limits.with(kind, value);
    }

    /** {@code DAILY_COUNT -> daily-count}: the Spring property spelling, for messages. */
    static String propertyName(LimitKind kind) {
        return kind.wireName().replace('_', '-');
    }

    static ParsedFile parse(String source, byte[] bytes) {
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            // A second "daily:" under one tier must not silently win.
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            root = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new PaymentLimitsPolicyException(source + " is not valid YAML: " + ex.getMessage(), ex);
        }
        Map<?, ?> top = asMap(source, "the document", root);
        for (Object key : top.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(String.valueOf(key))) {
                throw fail(source, "has unknown top-level key '" + key + "' (allowed: country, currency, tiers)");
            }
        }

        Object countryValue = top.get("country");
        if (!(countryValue instanceof String countryText)) {
            throw fail(source, "must state `country` as a string, e.g. country: \"ZW\"");
        }
        Country country;
        try {
            country = Country.valueOf(countryText.trim());
        } catch (IllegalArgumentException ex) {
            throw fail(source, "states unknown country '" + countryText + "'");
        }

        Object currencyValue = top.get("currency");
        if (!(currencyValue instanceof String currency) || !currency.matches("[A-Z]{3}")) {
            throw fail(source, "must state `currency` as an ISO 4217 code, e.g. currency: USD");
        }

        Map<?, ?> tiersNode = asMap(source, "`tiers`", top.get("tiers"));
        Map<KycTier, TierLimits> tiers = new EnumMap<>(KycTier.class);
        for (Object key : tiersNode.keySet()) {
            KycTier tier = KycTier.parse(String.valueOf(key))
                    .filter(t -> t.dbValue().equals(String.valueOf(key)))
                    .orElseThrow(() -> fail(source, "has unknown tier '" + key
                            + "' (allowed: basic, standard, enhanced)"));
            tiers.put(tier, parseTier(source, tier, tiersNode.get(key)));
        }
        for (KycTier tier : KycTier.values()) {
            if (!tiers.containsKey(tier)) {
                throw fail(source, "is missing tier '" + tier.dbValue()
                        + "' — every tier must be listed; write `" + tier.dbValue() + ": {}` for no limits");
            }
        }
        return new ParsedFile(country, currency, tiers);
    }

    private static TierLimits parseTier(String source, KycTier tier, Object node) {
        Map<?, ?> kinds = asMap(source, "tier '" + tier.dbValue() + "'", node);
        TierLimits limits = TierLimits.none();
        for (Map.Entry<?, ?> entry : kinds.entrySet()) {
            String key = String.valueOf(entry.getKey());
            LimitKind kind = LimitKind.fromWireName(key);
            if (kind == null) {
                throw fail(source, "tier '" + tier.dbValue() + "' has unknown limit '" + key
                        + "' (allowed: per_payment, daily, monthly, daily_count)");
            }
            String where = tier.dbValue() + "." + key;
            long value = positiveInteger(source, where, entry.getValue());
            if (kind == LimitKind.DAILY_COUNT && value > Integer.MAX_VALUE) {
                throw fail(source, where + " = " + value + " is not a sane payment count");
            }
            limits = limits.with(kind, value);
        }
        return limits;
    }

    private static long positiveInteger(String source, String where, Object value) {
        if (value == null) {
            throw fail(source, where + " has no value — to mean \"no limit\", omit the key entirely");
        }
        if (!(value instanceof Integer || value instanceof Long || value instanceof BigInteger)) {
            throw fail(source, where + " = " + value + " must be a whole number of MINOR units "
                    + "(or of payments, for daily_count) — not a decimal, a string or a boolean");
        }
        BigInteger big = new BigInteger(value.toString());
        if (big.signum() <= 0) {
            throw fail(source, where + " = " + value + " must be > 0 (omit the key for no limit)");
        }
        if (big.bitLength() > 62) {
            throw fail(source, where + " = " + value + " is out of range");
        }
        return big.longValue();
    }

    /**
     * The two consistency rules, as boot refusals. Applied to the FILE only;
     * what the overrides do to them is handled in {@link #load} (see the class
     * comment).
     */
    static void validate(String context, Map<KycTier, TierLimits> tiers) {
        for (KycTier tier : KycTier.values()) {
            TierLimits t = tiers.get(tier);
            requireOrdered(context, tier, LimitKind.PER_PAYMENT, t.perPaymentMinor(), LimitKind.DAILY, t.dailyMinor());
            requireOrdered(context, tier, LimitKind.DAILY, t.dailyMinor(), LimitKind.MONTHLY, t.monthlyMinor());
            requireOrdered(context, tier, LimitKind.PER_PAYMENT, t.perPaymentMinor(), LimitKind.MONTHLY, t.monthlyMinor());
        }
        List<TierInversion> inversions = tierInversions(tiers);
        if (!inversions.isEmpty()) {
            throw new PaymentLimitsPolicyException(inversions.get(0).describe(context)
                    + ". A higher tier must never be stricter than a lower one — upgrading "
                    + "would lower the customer's limits.");
        }
    }

    /** Every adjacent pair of tiers where the higher one is stricter for a kind (absent = unlimited). */
    static List<TierInversion> tierInversions(Map<KycTier, TierLimits> tiers) {
        List<TierInversion> inversions = new ArrayList<>();
        KycTier[] order = KycTier.values();
        for (int i = 1; i < order.length; i++) {
            KycTier lower = order[i - 1];
            KycTier higher = order[i];
            for (LimitKind kind : LimitKind.values()) {
                Long low = tiers.get(lower).get(kind);
                Long high = tiers.get(higher).get(kind);
                if (high == null) {
                    continue; // unlimited is never stricter
                }
                if (low == null || high < low) {
                    inversions.add(new TierInversion(lower, higher, kind, low, high));
                }
            }
        }
        return inversions;
    }

    /** A higher tier stricter than the tier below it for one kind. */
    record TierInversion(KycTier lower, KycTier higher, LimitKind kind, Long lowerValue, long higherValue) {

        String describe(String context) {
            return context + ": tier '" + higher.dbValue() + "' " + kind.wireName() + " (" + higherValue
                    + ") is STRICTER than tier '" + lower.dbValue() + "' " + kind.wireName() + " ("
                    + (lowerValue == null ? "none = unlimited" : lowerValue) + ")";
        }
    }

    private static void requireOrdered(String context, KycTier tier, LimitKind smallKind, Long small,
                                       LimitKind largeKind, Long large) {
        if (small != null && large != null && small > large) {
            throw new PaymentLimitsPolicyException(context + ": tier '" + tier.dbValue() + "' "
                    + smallKind.wireName() + " (" + small + ") exceeds its " + largeKind.wireName()
                    + " (" + large + "). Required: per_payment <= daily <= monthly.");
        }
    }

    private static Map<?, ?> asMap(String source, String what, Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            throw fail(source, what + " must be a mapping"
                    + (node == null ? " (use {} for an empty one)" : ""));
        }
        return map;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static PaymentLimitsPolicyException fail(String source, String problem) {
        return new PaymentLimitsPolicyException(source + " " + problem);
    }

    /** The file as written, before overrides. */
    record ParsedFile(Country country, String currency, Map<KycTier, TierLimits> tiers) {
    }
}
