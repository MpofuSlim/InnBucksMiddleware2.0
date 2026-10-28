package zw.co.innbucks.middleware.limits;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.stepup.StepUpProperties;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds THE {@link PaymentLimitsPolicy} at boot from the policy file packaged
 * into the image ({@code classpath:limits/limits-<iso>.yaml}, picked by
 * {@code INNBUCKS_COUNTRY}) plus any tighten-only overrides. A missing,
 * malformed or inconsistent file, a currency that is not the cell's, or a
 * looser override throws from the bean method — the context does not start.
 * An override that TIGHTENS never does, whatever it does to the file's
 * ordering rules (see {@link PaymentLimitsPolicyLoader}): the emergency lever
 * must not be able to take the cell down.
 *
 * <p>The location is NOT configurable, deliberately: a property naming another
 * file would be an env override of the limit values by another name. Tests
 * that need values use the overrides (any value tightens a dormant file) or
 * drive {@link PaymentLimitsPolicyLoader} with their own fixtures.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PaymentLimitsProperties.class)
public class PaymentLimitsConfig {

    @Bean
    public PaymentLimitsPolicy paymentLimitsPolicy(CountryProperties countryProperties,
                                                   CoreBankingPort corePort,
                                                   PaymentLimitsProperties properties,
                                                   StepUpProperties stepUpProperties) {
        String source = PaymentLimitsPolicyLoader.resourcePathFor(countryProperties.country());
        byte[] bytes = read(source);
        PaymentLimitsPolicy policy = PaymentLimitsPolicyLoader.load(source, bytes,
                countryProperties.country(), corePort.cellCurrency(), properties.asMap());

        log.info("Payment limits policy loaded: {} country={} currency={} sha256={}",
                policy.source(), policy.country(), policy.currency(), policy.sha256());
        for (KycTier tier : KycTier.values()) {
            log.info("Payment limits effective [{}]: {}", tier.dbValue(), policy.limitsFor(tier).describe());
        }
        if (policy.isDormant()) {
            log.warn("Payment limits are DORMANT: {} configures no limit for any tier (values pending "
                    + "compliance; absent = no limit). Withdrawals and transfers are bounded only by "
                    + "step-up approval and the core.", policy.source());
        }
        for (PaymentLimitsPolicy.AppliedOverride override : policy.overrides()) {
            if (override.derived()) {
                log.warn("EMERGENCY PAYMENT-LIMIT OVERRIDE ACTIVE (derived): {}. Lowered to follow another "
                        + "override in this tier, and goes away with it.", override.describe());
            } else {
                log.warn("EMERGENCY PAYMENT-LIMIT OVERRIDE ACTIVE: {}. Tighten-only, set from the environment, "
                        + "NOT from the reviewed policy file — move the value into {} and unset the override.",
                        override.describe(), policy.source());
            }
        }
        for (String warning : policy.warnings()) {
            log.warn("PAYMENT-LIMIT OVERRIDES LEAVE THE TIERS INVERTED: {}", warning);
        }
        for (String conflict : stepUpConflicts(policy, stepUpProperties)) {
            log.warn(conflict);
        }
        return policy;
    }

    /**
     * Tiers whose step-up threshold sits ABOVE their per-payment limit: every
     * payment big enough to need SMS approval is refused by the limit first,
     * so the approval can never fire for that tier.
     *
     * <p>WARN, not refuse, on purpose. The combination is strictly SAFER than
     * either setting alone — nothing that would have needed an approval can
     * move at all — so refusing boot would block a deploy for being too
     * cautious. And the thresholds still live in the box's env while the limits
     * are reviewed in the image: refusing would let a stale env value make a
     * reviewed compliance change undeployable. What the warning flags is
     * intent drift — someone meant "approve above X" and got "never above Y".
     */
    static List<String> stepUpConflicts(PaymentLimitsPolicy policy, StepUpProperties stepUp) {
        List<String> conflicts = new ArrayList<>();
        if (stepUp == null || !stepUp.enabled() || stepUp.thresholds() == null) {
            return conflicts;
        }
        for (KycTier tier : KycTier.values()) {
            Long perPayment = policy.limitsFor(tier).perPaymentMinor();
            long threshold = stepUp.thresholdFor(tier);
            if (perPayment != null && threshold > perPayment) {
                conflicts.add("Step-up threshold for tier '" + tier.dbValue() + "' (" + threshold
                        + ") is ABOVE its per_payment limit (" + perPayment + ") in " + policy.source()
                        + ": SMS approval can never fire for this tier, because every payment that "
                        + "would need it is refused by the limit first. Lower STEPUP_THRESHOLD_"
                        + tier.name() + " to at most " + perPayment + " if approval was intended.");
            }
        }
        return conflicts;
    }

    private static byte[] read(String source) {
        ClassPathResource resource = new ClassPathResource(source);
        if (!resource.exists()) {
            throw new PaymentLimitsPolicyException("No payment-limits policy packaged for this cell: "
                    + "classpath:" + source + " is missing. Every supported country needs one "
                    + "(write every tier as {} until compliance supplies values).");
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new PaymentLimitsPolicyException("Could not read classpath:" + source, ex);
        }
    }
}
