package zw.co.innbucks.middleware.customer;

/**
 * A decision would have REFUSED (limit or step-up) on a tier that could not be
 * read — see {@link KycTierResolution#authoritative()}. Mapped to 503
 * {@code kyc_tier_unavailable}: the client retries unchanged; it is never
 * told a limit it may not actually have.
 */
public class KycTierUnavailableException extends RuntimeException {

    public KycTierUnavailableException() {
        super("The customer's KYC tier could not be read; a refusal cannot rest on a guessed tier");
    }
}
