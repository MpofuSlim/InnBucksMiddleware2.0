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
 *       value tightens an absent one). The EFFECTIVE policy must then pass the
 *       two consistency rules above, so an override cannot leave the tiers
 *       or kinds contradicting each other.</li>
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
        List<AppliedOverride> applied = new ArrayList<>();
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
                applied.add(new AppliedOverride(tier, kind, fileValue, value));
            }
        }));
        if (!applied.isEmpty()) {
            validate(source + " with the emergency overrides applied", effective);
        }
        return new PaymentLimitsPolicy(cellCountry, file.currency(), source, sha256(bytes),
                effective, applied);
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
     * The two consistency rules. Shared by the file and the effective
     * (overridden) policy, so an override can never produce a combination a
     * reviewer could not have merged.
     */
    static void validate(String context, Map<KycTier, TierLimits> tiers) {
        for (KycTier tier : KycTier.values()) {
            TierLimits t = tiers.get(tier);
            requireOrdered(context, tier, LimitKind.PER_PAYMENT, t.perPaymentMinor(), LimitKind.DAILY, t.dailyMinor());
            requireOrdered(context, tier, LimitKind.DAILY, t.dailyMinor(), LimitKind.MONTHLY, t.monthlyMinor());
            requireOrdered(context, tier, LimitKind.PER_PAYMENT, t.perPaymentMinor(), LimitKind.MONTHLY, t.monthlyMinor());
        }
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
                    throw new PaymentLimitsPolicyException(context + ": tier '" + higher.dbValue() + "' "
                            + kind.wireName() + " (" + high + ") is STRICTER than tier '" + lower.dbValue()
                            + "' " + kind.wireName() + " (" + (low == null ? "none = unlimited" : low)
                            + "). A higher tier must never be stricter than a lower one — upgrading "
                            + "would lower the customer's limits.");
                }
            }
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
