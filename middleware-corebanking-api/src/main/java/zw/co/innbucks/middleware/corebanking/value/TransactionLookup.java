package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * Everything an adapter needs to reconcile a movement. The ref alone is not
 * enough: cores index transactions under their ACCOUNT (Fineract's
 * transaction-by-external-id read lives under the savings account), so the
 * caller — who holds the ledger row — supplies the movement kind and the
 * account(s) the write targeted.
 */
public record TransactionLookup(
        TxRef externalRef,
        MovementKind kind,
        AccountRef sourceAccount,
        AccountRef destinationAccount
) {

    public TransactionLookup {
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case DEPOSIT -> Objects.requireNonNull(destinationAccount, "destinationAccount (DEPOSIT)");
            case WITHDRAWAL -> Objects.requireNonNull(sourceAccount, "sourceAccount (WITHDRAWAL)");
            case TRANSFER -> { /* transfers reconcile by ref alone where the core supports it */ }
        }
    }
}
