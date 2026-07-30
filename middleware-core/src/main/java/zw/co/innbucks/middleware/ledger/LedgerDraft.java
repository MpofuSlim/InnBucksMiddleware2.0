package zw.co.innbucks.middleware.ledger;

import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;

import java.util.Objects;
import java.util.UUID;

/**
 * Inputs for opening a ledger row. {@code externalRef} is the reference the
 * core call will carry (typically derived from the request's namespaced
 * idempotency key) — it is persisted with the PENDING row BEFORE the call so
 * an ambiguous outcome is always reconcilable.
 */
public record LedgerDraft(
        UUID customerId,
        LedgerTransactionType type,
        String sourceAccount,
        String destinationAccount,
        MinorUnits amount,
        String narrative,
        String externalRef,
        CoreProvider coreProvider
) {

    public LedgerDraft {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(coreProvider, "coreProvider");
        if (externalRef.isBlank() || externalRef.length() > 64) {
            throw new IllegalArgumentException("externalRef must be non-blank and at most 64 chars");
        }
        switch (type) {
            case DEPOSIT -> Objects.requireNonNull(destinationAccount, "destinationAccount (DEPOSIT)");
            case WITHDRAWAL -> Objects.requireNonNull(sourceAccount, "sourceAccount (WITHDRAWAL)");
            case TRANSFER -> {
                Objects.requireNonNull(sourceAccount, "sourceAccount (TRANSFER)");
                Objects.requireNonNull(destinationAccount, "destinationAccount (TRANSFER)");
            }
        }
    }
}
