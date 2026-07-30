package zw.co.innbucks.middleware.stepup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import zw.co.innbucks.middleware.customer.KycTier;

/**
 * Step-up (SMS re-approval) thresholds for money movement, per KYC tier.
 * A withdrawal or transfer whose amount is {@code >=} the caller's tier
 * threshold requires a fresh OTP-backed approval bound to that exact
 * transaction. Deposits never step up (money in, not out).
 *
 * <p>Thresholds are MINOR units of the cell currency — same unit the
 * movement endpoints take. Tune per market; the defaults are deliberately
 * conservative for the lowest tier.
 */
@Validated
@ConfigurationProperties(prefix = "innbucks.stepup")
public record StepUpProperties(

        /** Kill switch. Leave true everywhere real; false only for local debugging. */
        boolean enabled,

        @NotNull
        Thresholds thresholds
) {

    public record Thresholds(

            /** BASIC tier: movements >= this many minor units step up. */
            @Min(1)
            long basic,

            @Min(1)
            long standard,

            @Min(1)
            long enhanced
    ) {
    }

    /**
     * Threshold for a tier; an unknown/absent tier gets the BASIC (tightest)
     * threshold — fail closed, not open.
     */
    public long thresholdFor(KycTier tier) {
        if (tier == null) {
            return thresholds.basic();
        }
        return switch (tier) {
            case BASIC -> thresholds.basic();
            case STANDARD -> thresholds.standard();
            case ENHANCED -> thresholds.enhanced();
        };
    }
}
