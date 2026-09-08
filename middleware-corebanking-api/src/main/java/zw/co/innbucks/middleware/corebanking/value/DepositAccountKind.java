package zw.co.innbucks.middleware.corebanking.value;

/**
 * What kind of deposit account a statement is for. Every kind here is a
 * running-balance account in the core — the same statement document and the
 * same balance policy apply to all of them; the kind only decides how the
 * document is TITLED and which button the console showed to reach it.
 *
 * <p>Deliberately not the core's own enum: Fineract numbers these 100/200/300
 * (savings / fixed / recurring), Veengu will name them differently, and a
 * statement must not carry a core-specific code onto a customer's document.
 */
public enum DepositAccountKind {

    SAVINGS("Savings"),
    FIXED_DEPOSIT("Fixed Deposit"),
    RECURRING_DEPOSIT("Recurring Deposit"),
    /** A deposit type this middleware has no label for — rendered generically, never refused. */
    OTHER("Deposit");

    private final String displayName;

    DepositAccountKind(String displayName) {
        this.displayName = displayName;
    }

    /** Human label for document headings ("Fixed Deposit Statement"). */
    public String displayName() {
        return displayName;
    }
}
