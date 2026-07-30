package zw.co.innbucks.middleware.corebanking.command;

import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.util.Objects;

/**
 * A single-account movement (deposit or withdrawal — direction is the port
 * method, never the amount's sign). {@code externalRef} is middleware-minted
 * and persisted on our ledger row BEFORE the call, so an ambiguous outcome is
 * always reconcilable.
 */
public record MoneyMovementCommand(
        AccountRef account,
        MinorUnits amount,
        String narrative,
        TxRef externalRef
) {

    public MoneyMovementCommand {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(externalRef, "externalRef");
    }
}
