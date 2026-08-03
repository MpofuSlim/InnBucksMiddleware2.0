package zw.co.innbucks.middleware.corebanking.value;

/**
 * Balances for a single account. {@code available} excludes holds; {@code
 * current} is the posted balance.
 *
 * @param accountNumber the core's CUSTOMER-FACING account number (Fineract's
 *        {@code accountNo}, e.g. {@code 000000010}) — the number the customer
 *        sees in the app and on statements, as opposed to {@code account}'s
 *        externalId, which is a middleware-internal reference. Nullable: a
 *        core with no such notion simply doesn't set it. Used for display
 *        (masked tails in SMS), never for addressing.
 */
public record AccountBalance(
        AccountRef account,
        MinorUnits available,
        MinorUnits current,
        String accountNumber
) {

    public AccountBalance(AccountRef account, MinorUnits available, MinorUnits current) {
        this(account, available, current, null);
    }
}
