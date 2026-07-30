package zw.co.innbucks.middleware.corebanking;

/**
 * The core banking systems this middleware can be deployed against. Each
 * deployment cell pins exactly one via {@code INNBUCKS_CORE_PROVIDER} — this
 * is per-deployment configuration, never a runtime switch.
 */
public enum CoreProvider {
    FINERACT,
    VEENGU,
    /** Legacy — the retired OradianMiddleware integration; kept so ported data rows stay readable. */
    ORADIAN
}
