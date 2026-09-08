package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * A back-office operator's credential for THEIR core banking system, opaque
 * to everything above the adapter. For Fineract this is the raw
 * {@code Authorization: Basic …} header value the Mifos web app already
 * attaches to every call it makes; a Veengu adapter would carry that core's
 * token shape in the same field.
 *
 * <p>The middleware never stores, logs or interprets it — the ONLY legal use
 * is forwarding it to the adapter's own core so the core can say who this is
 * and what they may read. That is the whole design: back-office identity is
 * DELEGATED to the core, so this middleware still holds no operator identity
 * of its own.
 */
public record OperatorCredential(String authorizationHeader) {

    public OperatorCredential {
        Objects.requireNonNull(authorizationHeader, "authorizationHeader");
        if (authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("authorizationHeader must not be blank");
        }
    }

    /** Never expose the credential in logs, error messages or toString. */
    @Override
    public String toString() {
        return "OperatorCredential[redacted]";
    }
}
