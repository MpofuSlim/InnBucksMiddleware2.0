package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * Caller-supplied dedup key for a mutating operation. The middleware
 * namespaces the inbound header per customer BEFORE it reaches the port
 * (a client cannot collide with, or replay, another customer's key), so the
 * value here is already safe to forward verbatim to cores that support
 * server-side dedup.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("Idempotency key must be non-blank and at most 64 chars");
        }
    }
}
