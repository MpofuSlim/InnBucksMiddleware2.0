package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/** A deposit/wallet account's stable reference in the core (its externalId). */
public record AccountRef(String externalId) {

    public AccountRef {
        Objects.requireNonNull(externalId, "externalId");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must be non-blank");
        }
    }
}
