package zw.co.innbucks.middleware.transactions;

/** The caller tried to move money out of (or into) an account they don't own. Maps to 403. */
public class AccountOwnershipException extends RuntimeException {

    public AccountOwnershipException(String accountId) {
        super("Account does not belong to the authenticated customer: " + accountId);
    }
}
