package zw.co.innbucks.middleware.corebanking.value;

/** One deposit/wallet account as listed for a customer. */
public record DepositAccountSummary(
        AccountRef account,
        String name,
        String currencyCode,
        MinorUnits balance
) {
}
