package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

/**
 * One deposit/wallet account as listed for a customer.
 *
 * @param restriction the bank's block on the account as the core reports it —
 *                    never null; {@link AccountRestriction#NONE} when nothing
 *                    is blocked or the core does not say
 */
public record DepositAccountSummary(
        AccountRef account,
        String name,
        String currencyCode,
        MinorUnits balance,
        AccountRestriction restriction
) {

    public DepositAccountSummary {
        Objects.requireNonNull(restriction, "restriction — report AccountRestriction.NONE, never null");
    }
}
