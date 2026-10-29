package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.stepup.StepUpProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Every boot-refusal rule of the limits policy, without a Spring context. The
 * fixture ({@code limits-fixtures/valid-zw.yaml}) carries test values; the
 * invalid cases are small edits of it so each test breaks exactly one rule.
 */
class PaymentLimitsPolicyLoaderTest {

    private static final String SOURCE = "limits/limits-zw.yaml";

    private static byte[] fixture() {
        try (InputStream in = PaymentLimitsPolicyLoaderTest.class.getClassLoader()
                .getResourceAsStream("limits-fixtures/valid-zw.yaml")) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String fixtureText() {
        return new String(fixture(), StandardCharsets.UTF_8);
    }

    private static PaymentLimitsPolicy load(String yaml) {
        return load(yaml, Map.of());
    }

    private static PaymentLimitsPolicy load(String yaml, Map<KycTier, Map<LimitKind, Long>> overrides) {
        return PaymentLimitsPolicyLoader.load(SOURCE, yaml.getBytes(StandardCharsets.UTF_8),
                Country.ZW, "USD", overrides);
    }

    private static Map<KycTier, Map<LimitKind, Long>> override(KycTier tier, LimitKind kind, long value) {
        Map<KycTier, Map<LimitKind, Long>> map = new EnumMap<>(KycTier.class);
        map.put(tier, new EnumMap<>(Map.of(kind, value)));
        return map;
    }

    private static final String DORMANT = """
            country: ZW
            currency: USD
            tiers:
              basic: {}
              standard: {}
              enhanced: {}
            """;

    // --- Good files ------------------------------------------------------

    @Test
    void aValidFileLoadsWithItsValuesAndTheDigestOfItsExactBytes() throws Exception {
        PaymentLimitsPolicy policy = PaymentLimitsPolicyLoader.load(SOURCE, fixture(),
                Country.ZW, "USD", Map.of());

        assertThat(policy.limitsFor(KycTier.BASIC))
                .isEqualTo(new TierLimits(100000L, 150000L, 500000L, 10));
        assertThat(policy.limitsFor(KycTier.ENHANCED).dailyCount())
                .as("an absent kind stays absent = unlimited").isNull();
        assertThat(policy.isDormant()).isFalse();
        assertThat(policy.overrides()).isEmpty();
        assertThat(policy.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(fixture())));
        assertThat(policy.source()).isEqualTo(SOURCE);
        assertThat(policy.currency()).isEqualTo("USD");
    }

    @Test
    void theShippedShapeIsDormant() {
        PaymentLimitsPolicy policy = load(DORMANT);
        assertThat(policy.isDormant()).isTrue();
        for (KycTier tier : KycTier.values()) {
            assertThat(policy.limitsFor(tier).isDormant()).isTrue();
        }
    }

    @Test
    void aNullTierReadsAsBasic() {
        PaymentLimitsPolicy policy = load(fixtureText());
        assertThat(policy.limitsFor(null)).isEqualTo(policy.limitsFor(KycTier.BASIC));
    }

    // --- Cell identity ---------------------------------------------------

    @Test
    void aCurrencyThatIsNotTheCellCurrencyRefusesBoot() {
        assertThatThrownBy(() -> PaymentLimitsPolicyLoader.load(SOURCE, fixture(), Country.ZW, "ZWG", Map.of()))
                .isInstanceOf(PaymentLimitsPolicyException.class)
                .hasMessageContaining("currency USD")
                .hasMessageContaining("ZWG");
    }

    @Test
    void aFileForAnotherCountryRefusesBoot() {
        assertThatThrownBy(() -> PaymentLimitsPolicyLoader.load(SOURCE, fixture(), Country.KE, "USD", Map.of()))
                .isInstanceOf(PaymentLimitsPolicyException.class)
                .hasMessageContaining("country ZW")
                .hasMessageContaining("KE");
    }

    // --- Strict parsing --------------------------------------------------

    @Test
    void zeroRefuses() {
        assertRefused(fixtureText().replace("daily_count: 10", "daily_count: 0"), "must be > 0");
    }

    @Test
    void aNegativeValueRefuses() {
        assertRefused(fixtureText().replace("per_payment: 100000\n", "per_payment: -5\n"), "must be > 0");
    }

    @Test
    void aDecimalRefuses() {
        assertRefused(fixtureText().replace("per_payment: 100000\n", "per_payment: 1000.00\n"),
                "whole number of MINOR units");
    }

    @Test
    void aQuotedNumberRefuses() {
        assertRefused(fixtureText().replace("per_payment: 100000\n", "per_payment: \"100000\"\n"),
                "whole number of MINOR units");
    }

    @Test
    void aKeyWithNoValueRefusesRatherThanReadingAsUnlimited() {
        assertRefused(fixtureText().replace("daily: 150000", "daily:"), "omit the key entirely");
    }

    @Test
    void anUnknownKindRefuses() {
        assertRefused(fixtureText().replace("daily: 150000", "dialy: 150000"), "unknown limit 'dialy'");
    }

    @Test
    void anUnknownTierRefuses() {
        assertRefused(DORMANT.replace("standard: {}", "standrd: {}"), "unknown tier 'standrd'");
    }

    @Test
    void aTierNameInTheWrongCaseRefuses() {
        assertRefused(DORMANT.replace("basic: {}", "Basic: {}"), "unknown tier 'Basic'");
    }

    @Test
    void aMissingTierRefuses() {
        assertRefused(DORMANT.replace("  enhanced: {}\n", ""), "missing tier 'enhanced'");
    }

    @Test
    void aTierWithNoMappingRefuses() {
        assertRefused(DORMANT.replace("basic: {}", "basic:"), "use {} for an empty one");
    }

    @Test
    void anUnknownTopLevelKeyRefuses() {
        assertRefused(DORMANT + "stepup: 5\n", "unknown top-level key 'stepup'");
    }

    @Test
    void aDuplicateKeyRefusesInsteadOfTheLastOneWinning() {
        assertRefused(fixtureText().replace("daily: 150000", "daily: 150000\n    daily: 900000"),
                "not valid YAML");
    }

    @Test
    void aMissingCurrencyRefuses() {
        assertRefused(DORMANT.replace("currency: USD\n", ""), "ISO 4217");
    }

    @Test
    void anUnknownCountryRefuses() {
        assertRefused(DORMANT.replace("country: ZW", "country: XX"), "unknown country 'XX'");
    }

    @Test
    void notAMappingAtAllRefuses() {
        assertRefused("- just\n- a list\n", "must be a mapping");
    }

    // --- Consistency -----------------------------------------------------

    @Test
    void perPaymentAboveDailyRefuses() {
        assertRefused(fixtureText().replace("per_payment: 100000\n", "per_payment: 160000\n"),
                "per_payment (160000) exceeds its daily (150000)");
    }

    @Test
    void dailyAboveMonthlyRefuses() {
        assertRefused(fixtureText().replace("daily: 150000", "daily: 600000"),
                "daily (600000) exceeds its monthly (500000)");
    }

    @Test
    void perPaymentAboveMonthlyRefusesEvenWithNoDailyBetweenThem() {
        // Per-tier ordering is checked before the cross-tier rule, so basic's
        // missing daily (which also makes standard's daily look stricter) does
        // not mask the per_payment > monthly fault.
        String yaml = fixtureText()
                .replace("    daily: 150000\n", "")
                .replace("per_payment: 100000\n", "per_payment: 600000\n");
        assertRefused(yaml, "tier 'basic' per_payment (600000) exceeds its monthly (500000)");
    }

    @Test
    void aHigherTierStricterThanALowerOneRefuses() {
        assertRefused(fixtureText().replace("monthly: 1500000\n", "monthly: 450000\n"),
                "tier 'standard' monthly (450000) is STRICTER than tier 'basic' monthly (500000)");
    }

    @Test
    void aHigherTierLimitedWhereALowerOneIsUnlimitedRefuses() {
        // basic and standard leave daily_count unlimited; enhanced may not cap it.
        String yaml = fixtureText()
                .replace("    daily_count: 10\n", "")
                .replace("    daily_count: 20\n", "")
                .replace("    monthly: 10000000\n", "    monthly: 10000000\n    daily_count: 50\n");
        assertRefused(yaml, "tier 'enhanced' daily_count (50) is STRICTER than tier 'standard' daily_count "
                + "(none = unlimited)");
    }

    @Test
    void equalValuesAcrossTiersAreAllowed() {
        String yaml = fixtureText().replace("daily_count: 20", "daily_count: 10");
        assertThat(load(yaml).limitsFor(KycTier.STANDARD).dailyCount()).isEqualTo(10);
    }

    // --- Tighten-only overrides -------------------------------------------

    @Test
    void aLooserOverrideRefusesBoot() {
        assertThatThrownBy(() -> load(fixtureText(), override(KycTier.BASIC, LimitKind.DAILY, 150001L)))
                .isInstanceOf(PaymentLimitsPolicyException.class)
                .hasMessageContaining("innbucks.limits.override.basic.daily=150001 is LOOSER")
                .hasMessageContaining("only TIGHTEN");
    }

    @Test
    void aTighterOverrideIsAppliedAndRecorded() {
        PaymentLimitsPolicy policy = load(fixtureText(), override(KycTier.BASIC, LimitKind.DAILY, 120000L));

        assertThat(policy.limitsFor(KycTier.BASIC).dailyMinor()).isEqualTo(120000L);
        assertThat(policy.limitsFor(KycTier.BASIC).monthlyMinor()).as("other kinds untouched").isEqualTo(500000L);
        assertThat(policy.overrides()).containsExactly(
                new PaymentLimitsPolicy.AppliedOverride(KycTier.BASIC, LimitKind.DAILY, 150000L, 120000L));
    }

    @Test
    void anOverrideMayIntroduceALimitTheFileLeavesUnlimited() {
        PaymentLimitsPolicy policy = load(DORMANT, override(KycTier.BASIC, LimitKind.DAILY_COUNT, 3L));

        assertThat(policy.limitsFor(KycTier.BASIC).dailyCount()).isEqualTo(3);
        assertThat(policy.isDormant()).isFalse();
        assertThat(policy.overrides()).containsExactly(
                new PaymentLimitsPolicy.AppliedOverride(KycTier.BASIC, LimitKind.DAILY_COUNT, null, 3L));
    }

    @Test
    void anOverrideEqualToTheFileChangesNothingAndIsNotReported() {
        PaymentLimitsPolicy policy = load(fixtureText(), override(KycTier.BASIC, LimitKind.DAILY, 150000L));
        assertThat(policy.overrides()).isEmpty();
    }

    @Test
    void aZeroOverrideRefuses() {
        assertThatThrownBy(() -> load(fixtureText(), override(KycTier.BASIC, LimitKind.MONTHLY, 0L)))
                .isInstanceOf(PaymentLimitsPolicyException.class)
                .hasMessageContaining("not a positive value");
    }

    /**
     * The emergency lever must never take the cell down for being too
     * cautious. Clamping basic's daily below its per_payment lowers per_payment
     * with it (recorded as derived) instead of refusing boot.
     */
    @Test
    void anOverrideBelowPerPaymentLowersPerPaymentInsteadOfRefusingBoot() {
        PaymentLimitsPolicy policy = load(fixtureText(), override(KycTier.BASIC, LimitKind.DAILY, 90000L));

        assertThat(policy.limitsFor(KycTier.BASIC)).isEqualTo(new TierLimits(90000L, 90000L, 500000L, 10));
        assertThat(policy.overrides()).containsExactly(
                new PaymentLimitsPolicy.AppliedOverride(KycTier.BASIC, LimitKind.PER_PAYMENT, 100000L, 90000L,
                        LimitKind.DAILY),
                new PaymentLimitsPolicy.AppliedOverride(KycTier.BASIC, LimitKind.DAILY, 150000L, 90000L));
        assertThat(policy.overrides().get(0).describe())
                .contains("basic.per_payment = 90000 (policy file: 100000; lowered to this tier's effective daily");
        assertThat(policy.warnings()).isEmpty();
    }

    @Test
    void anOverrideBelowDailyLowersDailyAndPerPaymentInOrder() {
        PaymentLimitsPolicy policy = load(fixtureText(), override(KycTier.BASIC, LimitKind.MONTHLY, 80000L));

        assertThat(policy.limitsFor(KycTier.BASIC)).isEqualTo(new TierLimits(80000L, 80000L, 80000L, 10));
        assertThat(policy.overrides()).extracting(PaymentLimitsPolicy.AppliedOverride::kind,
                        PaymentLimitsPolicy.AppliedOverride::derivedFrom)
                .containsExactly(
                        // per_payment follows the daily it was just lowered to.
                        tuple(LimitKind.PER_PAYMENT, LimitKind.DAILY),
                        tuple(LimitKind.DAILY, LimitKind.MONTHLY),
                        tuple(LimitKind.MONTHLY, null));
    }

    @Test
    void anAbsentPerPaymentIsNeverInventedByTheLowering() {
        String yaml = fixtureText().replace("    per_payment: 100000\n", "")
                .replace("    per_payment: 200000\n", "").replace("    per_payment: 1000000\n", "");
        PaymentLimitsPolicy policy = load(yaml, override(KycTier.BASIC, LimitKind.DAILY, 90000L));
        assertThat(policy.limitsFor(KycTier.BASIC).perPaymentMinor()).isNull();
        assertThat(policy.overrides()).hasSize(1);
    }

    /**
     * Why lowering is safe to do silently-but-logged: it changes no payment's
     * outcome. Over a grid of usage and amounts, the incoherent limits and the
     * lowered ones accept exactly the same payments (a day lies inside its
     * month, so the month's usage is never below the day's).
     */
    @Test
    void theLoweringNeverChangesWhetherAPaymentPasses() {
        TierLimits lowered = load(fixtureText(), override(KycTier.BASIC, LimitKind.MONTHLY, 90000L))
                .limitsFor(KycTier.BASIC);
        assertThat(lowered).isEqualTo(new TierLimits(90000L, 90000L, 90000L, 10));
        // What enforcing the override WITHOUT the lowering would have meant,
        // and another out-of-order shape that lowers to the same limits.
        TierLimits asOverridden = new TierLimits(100000L, 150000L, 90000L, 10);
        TierLimits incoherent = new TierLimits(100000L, 120000L, 90000L, 10);
        LimitWindows windows = LimitWindows.at(Instant.parse("2026-10-28T08:00:00Z"),
                Country.ZW.zoneId());
        long[] used = {0, 1, 30000, 89999, 90000, 100000, 120000, 160000};
        long[] amounts = {1, 50000, 89999, 90000, 90001, 100000, 100001, 120001, 150001};
        for (TierLimits raw : new TierLimits[] {incoherent, asOverridden}) {
            for (long day : used) {
                for (long monthExtra : used) {
                    LimitUsage usage = new LimitUsage(day, 0, 3, day + monthExtra, 0);
                    for (long amount : amounts) {
                        assertThat(PaymentLimitService.evaluate(lowered, usage, amount, windows, KycTier.BASIC)
                                .isPresent())
                                .as("day=%d month=%d amount=%d", day, day + monthExtra, amount)
                                .isEqualTo(PaymentLimitService.evaluate(raw, usage, amount, windows,
                                        KycTier.BASIC).isPresent());
                    }
                }
            }
        }
    }

    /**
     * The reviewers' scenario: the shipped file is dormant, and during a fraud
     * wave the operator clamps STANDARD's daily. Basic stays unlimited, so
     * standard is now "stricter than basic" — a WARNING, not a dead cell.
     */
    @Test
    void anOverrideThatMakesAHigherTierStricterIsAWarningNotARefusal() {
        PaymentLimitsPolicy policy = load(DORMANT, override(KycTier.STANDARD, LimitKind.DAILY, 100000L));

        assertThat(policy.limitsFor(KycTier.STANDARD).dailyMinor()).isEqualTo(100000L);
        assertThat(policy.limitsFor(KycTier.BASIC).dailyMinor()).as("never lowered to match").isNull();
        assertThat(policy.warnings()).singleElement().asString()
                .contains("with the emergency overrides applied")
                .contains("tier 'standard' daily (100000) is STRICTER than tier 'basic' daily (none = unlimited)")
                .contains("NOT refused");

        // And capping only the top tier, against a file that has values.
        PaymentLimitsPolicy top = load(fixtureText(), override(KycTier.ENHANCED, LimitKind.DAILY_COUNT, 15L));
        assertThat(top.limitsFor(KycTier.ENHANCED).dailyCount()).isEqualTo(15);
        assertThat(top.limitsFor(KycTier.STANDARD).dailyCount()).isEqualTo(20);
        assertThat(top.warnings()).singleElement().asString()
                .contains("tier 'enhanced' daily_count (15) is STRICTER than tier 'standard' daily_count (20)");
    }

    @Test
    void emptyOverrideEnvValuesBindToNoOverride() {
        // application.yaml maps every override to ${LIMITS_OVERRIDE_...:} — an
        // unset env var must bind to "no override", never to a zero.
        Map<String, String> source = Map.of(
                "innbucks.limits.override.basic.per-payment", "",
                "innbucks.limits.override.basic.daily", "120000",
                "innbucks.limits.override.basic.daily-count", "",
                "innbucks.limits.override.enhanced.monthly", "");
        PaymentLimitsProperties bound = new Binder(new MapConfigurationPropertySource(source))
                .bind("innbucks.limits", PaymentLimitsProperties.class)
                .orElseGet(PaymentLimitsProperties::none);

        assertThat(bound.asMap()).containsOnlyKeys(KycTier.BASIC);
        assertThat(bound.asMap().get(KycTier.BASIC)).containsExactly(Map.entry(LimitKind.DAILY, 120000L));
    }

    // --- Step-up vs per_payment --------------------------------------------

    @Test
    void aStepUpThresholdAbovePerPaymentIsReported() {
        PaymentLimitsPolicy policy = load(fixtureText());
        StepUpProperties stepUp = new StepUpProperties(true,
                new StepUpProperties.Thresholds(150000L, 200000L, 1000000L));

        assertThat(PaymentLimitsConfig.stepUpConflicts(policy, stepUp))
                .singleElement().asString()
                .contains("tier 'basic' (150000) is ABOVE its per_payment limit (100000)");
    }

    @Test
    void aStepUpThresholdAtOrBelowPerPaymentIsFine() {
        PaymentLimitsPolicy policy = load(fixtureText());
        StepUpProperties stepUp = new StepUpProperties(true,
                new StepUpProperties.Thresholds(100000L, 50000L, 1000000L));
        assertThat(PaymentLimitsConfig.stepUpConflicts(policy, stepUp)).isEmpty();
        // Disabled step-up has nothing to conflict with.
        StepUpProperties off = new StepUpProperties(false,
                new StepUpProperties.Thresholds(9_000_000L, 9_000_000L, 9_000_000L));
        assertThat(PaymentLimitsConfig.stepUpConflicts(policy, off)).isEmpty();
        // Neither does a dormant policy.
        assertThat(PaymentLimitsConfig.stepUpConflicts(load(DORMANT), stepUp)).isEmpty();
    }

    private static void assertRefused(String yaml, String messageFragment) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(PaymentLimitsPolicyException.class)
                .hasMessageContaining(messageFragment)
                .hasMessageContaining(SOURCE);
    }
}
