package zw.co.innbucks.middleware.corebanking.value;

/**
 * The core-neutral classification of a loan transaction, as far as a
 * statement needs to know. Adapters map their core's (much larger) type
 * catalogue onto these; nothing above the port ever sees a core type code.
 *
 * <p>The kind decides which TOTAL a line feeds. What it does to the principal
 * balance is carried separately on the entry
 * ({@link LoanTransactionEntry#principalDeltaMinor()}), because within one
 * kind the effect varies by core and by portion — a repayment reduces
 * principal by its principal PORTION, not by its amount.
 */
public enum LoanEntryKind {

    /** Money lent out to the borrower — the principal balance rises by the amount. */
    DISBURSEMENT,

    /**
     * Money received against the loan, in any of the core's repayment-shaped
     * forms (repayment, down payment, goodwill credit, merchant/payout refund,
     * charge adjustment, recovery). Split into principal / interest / fee /
     * penalty portions by the core.
     */
    REPAYMENT,

    /** Interest or charges forgiven — an amount with (usually) no principal effect. */
    WAIVER,

    /** Principal, interest and charges written off — the balance falls without money moving. */
    WRITE_OFF,

    /**
     * Anything else that can move the principal balance: a refund back to
     * the borrower, a chargeback, capitalised income, an adjustment. Shown
     * with its amount and its principal effect; feeds no headline total.
     */
    OTHER,

    /**
     * Bookkeeping the core records as a transaction but which moves nothing
     * the borrower owes — an accrual, a transfer-status marker, a
     * classification such as a charge-off. Shown, never totalled, never
     * walked into the balance. The loan analogue of a savings
     * {@code balanceNeutral} entry.
     */
    NONE
}
