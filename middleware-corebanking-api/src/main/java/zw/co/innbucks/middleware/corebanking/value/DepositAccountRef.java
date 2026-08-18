package zw.co.innbucks.middleware.corebanking.value;

/**
 * One of a customer's deposit accounts, WITHOUT its balance.
 *
 * <p>This exists because almost nothing that lists a customer's accounts
 * actually wants their money. Ownership checks, recipient resolution and the
 * statement's account lookup all need to know <em>which accounts exist and what
 * currency they are in</em> — and on a core that prices a balance read per
 * account, fetching balances to answer that question is the single most
 * expensive habit in the service.
 * {@link zw.co.innbucks.middleware.corebanking.CoreBankingPort#listDepositAccountRefs}
 * is the cheap question; {@code listDepositAccounts} stays for the one caller
 * ({@code GET /me/accounts}) that genuinely renders money.
 *
 * @param account       the stable core reference — what {@code /transactions/*} accepts
 * @param name          the product name as the core knows it ("InnBucks Wallet")
 * @param currencyCode  ISO code; already validated against the cell's currency
 *                      by the adapter, exactly as the balance path validates it
 * @param accountNumber the core's customer-facing account number, when the core
 *                      returns one in its listing — display only, and NULLABLE:
 *                      a core that does not list it leaves this null rather than
 *                      inviting an extra call to find out
 */
public record DepositAccountRef(
        AccountRef account,
        String name,
        String currencyCode,
        String accountNumber
) {
}
