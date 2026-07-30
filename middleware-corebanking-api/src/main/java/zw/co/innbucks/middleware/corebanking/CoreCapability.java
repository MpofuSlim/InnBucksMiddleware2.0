package zw.co.innbucks.middleware.corebanking;

/**
 * Optional core behaviours. Orchestration branches on these flags instead of
 * on the adapter type, so a new core slots in without touching domain code.
 */
public enum CoreCapability {

    /**
     * The core deduplicates mutating requests server-side when the adapter
     * propagates the idempotency key (Fineract: {@code Idempotency-Key}
     * header on commands). Without it, the middleware's own idempotency
     * claim-row is the only dedup for that core.
     */
    SERVER_SIDE_DEDUP,

    /**
     * The caller assigns the customer externalId at creation (Fineract:
     * {@code externalId} on POST /v1/clients = our customer UUID, making a
     * crashed create recoverable by GET). Absent, the core assigns it and the
     * middleware must persist the returned value after the call (Oradian's
     * model).
     */
    CLIENT_ASSIGNED_EXTERNAL_ID,

    /** The core supports reversing a posted transaction (Veengu: yes; Fineract: adjust/undo per type). */
    REVERSAL
}
