package zw.co.innbucks.middleware.corebanking.value;

import java.util.Objects;

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
 * @param restriction   the bank's block on the account, from the SAME listing —
 *                      never null; {@link AccountRestriction#NONE} when nothing
 *                      is blocked or the core does not say. Carried here
 *                      because the ownership check already pays for this
 *                      listing: refusing a debit from a blocked account early,
 *                      and deciding whether a blocked customer's session may
 *                      continue, then cost no extra core call
 */
public record DepositAccountRef(
        AccountRef account,
        String name,
        String currencyCode,
        String accountNumber,
        AccountRestriction restriction
) {

    public DepositAccountRef {
        Objects.requireNonNull(restriction, "restriction — report AccountRestriction.NONE, never null");
    }
}
