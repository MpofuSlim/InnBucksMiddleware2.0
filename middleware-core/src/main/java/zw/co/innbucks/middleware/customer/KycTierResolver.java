package zw.co.innbucks.middleware.customer;

/**
 * THE seam for "which KYC tier is this customer on, for this request".
 *
 * <p>Call it ONCE per request and pass the result on — to step-up, to the
 * advisory limit pre-check and into the binding check inside the ledger
 * transaction. Never re-derive the tier further down, and never read it from
 * the access token's {@code kyc_tier} claim (up to an access-token TTL old).
 *
 * <p>Today the only implementation reads the local customer row the request
 * already loaded ({@link CustomerRecordKycTierResolver}). When the approved
 * tier moves to the core, a live read slots in here: it must run OUTSIDE any
 * database transaction (the binding check's transaction must hold no network
 * call), and must report a failed read as
 * {@link KycTierResolution#unavailable()} rather than guess — never a cache
 * that decides money (CLAUDE.md caching policy).
 */
public interface KycTierResolver {

    /**
     * @param customer the row the caller already loaded for this request
     * @return never null; never throws for a bad stored value
     */
    KycTierResolution resolve(Customer customer);
}
