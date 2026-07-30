package zw.co.innbucks.middleware.corebanking.command;

import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TxRef;

import java.util.Objects;

/** Account-to-account transfer within the core. Same externalRef contract as MoneyMovementCommand. */
public record TransferCommand(
        AccountRef from,
        AccountRef to,
        MinorUnits amount,
        String narrative,
        TxRef externalRef
) {

    public TransferCommand {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(externalRef, "externalRef");
        if (from.equals(to)) {
            throw new IllegalArgumentException("Transfer source and destination must differ");
        }
    }
}
