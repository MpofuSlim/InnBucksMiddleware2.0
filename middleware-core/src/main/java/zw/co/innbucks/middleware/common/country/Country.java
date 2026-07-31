package zw.co.innbucks.middleware.common.country;

import java.time.ZoneId;

/**
 * Markets this codebase can be deployed into. One value is pinned per
 * deployment via {@code INNBUCKS_COUNTRY}; the app refuses to start without
 * it, and {@code MsisdnNormalizerRegistry} refuses to start unless the active
 * country has a normalizer.
 *
 * <p>Adding a market means adding the enum constant AND its
 * {@code MsisdnNormalizer} — the registry's startup check makes a half-done
 * addition fail loudly at boot rather than at the first customer registration.
 */
public enum Country {

    /** Kenya (+254). */
    KE("Africa/Nairobi"),

    /** Zimbabwe (+263). */
    ZW("Africa/Harare");

    private final String zoneId;

    Country(String zoneId) {
        this.zoneId = zoneId;
    }

    /**
     * The market's civil time zone, used ONLY to render timestamps in
     * customer-facing copy (transaction SMS). Everything stored and logged
     * stays UTC — a customer reading "14.05" for a payment they made at
     * 16.05 local is the exact class of bug this exists to prevent.
     */
    public ZoneId zoneId() {
        return ZoneId.of(zoneId);
    }
}
