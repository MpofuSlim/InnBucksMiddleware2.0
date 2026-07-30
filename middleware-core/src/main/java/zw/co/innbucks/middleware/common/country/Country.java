package zw.co.innbucks.middleware.common.country;

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
    KE,

    /** Zimbabwe (+263). */
    ZW
}
