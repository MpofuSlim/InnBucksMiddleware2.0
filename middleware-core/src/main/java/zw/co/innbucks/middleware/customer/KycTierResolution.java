package zw.co.innbucks.middleware.customer;

import java.util.Objects;

/**
 * The KYC tier a request is evaluated under, plus where it came from. Produced
 * ONCE per request by {@link KycTierResolver} and threaded into step-up and
 * both limit checks, so the three can never disagree about the tier.
 *
 * <p>{@link #tier()} is always usable for ALLOWING: on a failed read it is
 * BASIC, the tightest. What a non-{@linkplain #authoritative() authoritative}
 * resolution must never do is back a REFUSAL — a customer whose real tier
 * could not be read must not be told {@code limit_exceeded} with
 * {@code kycTier: basic} and offered "Raise my limits", nor be sent an SMS on a
 * guessed threshold. Callers that would refuse on it answer
 * {@link KycTierUnavailableException} (503, retryable) instead.
 */
public record KycTierResolution(KycTier tier, Source source) {

    public enum Source {
        /** Read from the customer record and recognised. */
        CUSTOMER_RECORD,
        /**
         * Read, but the stored value is not a tier name (garbage, a value from a
         * newer release). Mapped to BASIC, counted and logged. Still an
         * authoritative READ — of a bad value — so refusals may rest on it.
         */
        UNRECOGNISED_VALUE,
        /**
         * The tier could not be read at all. Cannot happen while the source is
         * the local row (the row was just loaded); exists so a live core read
         * can express failure without a signature change.
         */
        UNAVAILABLE
    }

    public KycTierResolution {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(source, "source");
    }

    public static KycTierResolution of(KycTier tier) {
        return new KycTierResolution(tier, Source.CUSTOMER_RECORD);
    }

    public static KycTierResolution unrecognised() {
        return new KycTierResolution(KycTier.BASIC, Source.UNRECOGNISED_VALUE);
    }

    public static KycTierResolution unavailable() {
        return new KycTierResolution(KycTier.BASIC, Source.UNAVAILABLE);
    }

    /** Whether a refusal ({@code limit_exceeded}, {@code step_up_required}) may rest on this tier. */
    public boolean authoritative() {
        return source != Source.UNAVAILABLE;
    }
}
