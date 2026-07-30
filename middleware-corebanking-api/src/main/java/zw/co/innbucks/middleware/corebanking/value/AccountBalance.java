package zw.co.innbucks.middleware.corebanking.value;

/** Balances for a single account. {@code available} excludes holds; {@code current} is the posted balance. */
public record AccountBalance(
        AccountRef account,
        MinorUnits available,
        MinorUnits current
) {
}
