package zw.co.innbucks.middleware.corebanking.value;

/**
 * A restriction the BANK has put on one deposit account, as the core reports
 * it on the customer's account listing. Staff set and lift it in the core;
 * this middleware only reads it.
 *
 * <p>Core-neutral on purpose: Fineract carries it as a savings sub-status
 * (BLOCK / BLOCK_DEBIT / BLOCK_CREDIT), Veengu will name it differently, and
 * nothing above an adapter may branch on a core's own vocabulary.
 *
 * <p><b>Advisory, never authoritative for money.</b> It lets the middleware
 * refuse a debit before any write, and decide whether a session may continue,
 * but the core still enforces the block on every write it receives — a stale
 * or missing value can only cost an early answer, never move money past a
 * block. An adapter whose core does not report restrictions, or does not
 * report one for an account, reports {@link #NONE}; the core's own refusal
 * stays the backstop.
 */
public enum AccountRestriction {

    /** Nothing blocked (or nothing reported). */
    NONE,

    /** Payments OUT are refused; money can still arrive. The bank's "pause payments" lever. */
    DEBITS_BLOCKED,

    /** Money IN is refused; the customer can still pay out. */
    CREDITS_BLOCKED,

    /**
     * Both directions refused — the account is frozen. In Fineract this is
     * also what a debit block plus a credit block becomes: the sub-status holds
     * one value, and blocking the second direction turns the first into a full
     * block.
     */
    FULLY_BLOCKED;

    /** True when the core will refuse to debit this account. */
    public boolean blocksDebits() {
        return this == DEBITS_BLOCKED || this == FULLY_BLOCKED;
    }

    /** True when the core will refuse to credit this account. */
    public boolean blocksCredits() {
        return this == CREDITS_BLOCKED || this == FULLY_BLOCKED;
    }
}
