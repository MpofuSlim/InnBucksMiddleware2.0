package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/** The customer's stable reference in the core (its externalId), never a core-internal numeric id. */
public record CoreCustomerRef(String externalId) {

    public CoreCustomerRef {
        Objects.requireNonNull(externalId, "externalId");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must be non-blank");
        }
    }
}
