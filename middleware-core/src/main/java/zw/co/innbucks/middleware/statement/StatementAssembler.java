package zw.co.innbucks.middleware.statement;

import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns core transaction entries into a balanced {@link StatementDocument}.
 * Pure — no Spring, no I/O — so every balance rule below is pinned by a unit
 * test that needs nothing but this class.
 *
 * <p><b>Balance policy: the core is the book of record; arithmetic fills its
 * silences.</b> Concretely, in priority order:
 *
 * <ol>
 *   <li><b>Opening balance</b> is the {@code runningBalance} of the latest
 *       entry BEFORE the period (fetched by the caller as a one-entry
 *       newest-first query). Summing fetched rows instead would go wrong on
 *       the first back-dated teller correction.</li>
 *   <li>If the account has NO history before the period, opening is zero —
 *       the one case where zero is a fact, not a guess.</li>
 *   <li>If pre-period history exists but the anchor entry carries no
 *       running balance, the opening is back-derived from the first in-period
 *       entry that does carry one. If nothing in reach carries one, the
 *       statement is REFUSED ({@link StatementUnavailableException}) — a
 *       statement with invented balances is worse than no statement.</li>
 *   <li><b>Per-line balances</b> prefer the core's reported running balance;
 *       arithmetic ({@code previous ± amount}) fills entries where the core
 *       reports none. When the core's value disagrees with the arithmetic,
 *       the core wins and the disagreement is counted for the caller to
 *       surface — never silently absorbed, never overriding the core.</li>
 *   <li><b>Reversed entries are balance-neutral</b>: shown, flagged, excluded
 *       from totals, and never used as a balance anchor (the core's balance
 *       semantics for a reversed row are not a contract we rely on).</li>
 *   <li><b>Entries the core itself records as moving no money</b> (a waived
 *       charge, an accrual, a transfer-status marker —
 *       {@code TransactionEntry.balanceNeutral()}) get the same treatment:
 *       shown with their amount, excluded from the credit/debit totals, and
 *       contributing zero to the balance walk. Counting a waived charge as a
 *       debit made {@code opening + credits − debits} disagree with the
 *       closing balance by exactly the waived amount — the document
 *       disagreeing with itself. Unlike reversed rows their running balance
 *       IS trustworthy (it is simply the unchanged balance), so they may
 *       carry one and it participates like any other.</li>
 * </ol>
 */
final class StatementAssembler {

    private StatementAssembler() {
    }

    /** The assembled document plus how often the core's balances disagreed with its amounts. */
    record Result(StatementDocument document, int balanceMismatches) {
    }

    /**
     * @param openingAnchorMinor  running balance of the latest pre-period
     *                            entry, when the core reported one; null otherwise
     * @param historyBeforePeriod whether ANY entry exists before the period —
     *                            distinguishes "new account, opening is zero"
     *                            from "opening unknown"
     * @param chronological       the period's entries OLDEST FIRST
     */
    static Result assemble(String accountId, String currencyCode, String customerName, String msisdn,
                           LocalDate from, LocalDate to, Instant generatedAt, ZoneId displayZone,
                           Long openingAnchorMinor, boolean historyBeforePeriod,
                           List<TransactionEntry> chronological) {

        long opening = openingBalance(openingAnchorMinor, historyBeforePeriod, chronological);

        List<StatementLine> lines = new ArrayList<>(chronological.size());
        long balance = opening;
        long credits = 0;
        long debits = 0;
        int mismatches = 0;

        for (TransactionEntry entry : chronological) {
            if (entry.reversed()) {
                lines.add(line(entry, balance));
                continue;
            }
            if (entry.balanceNeutral()) {
                // Shown with its amount, but it moved no money: nothing into
                // the totals, nothing into the balance walk. Its running
                // balance (the unchanged balance) still gets the core-wins
                // treatment, so a core that disagrees is surfaced, not hidden.
                if (entry.runningBalance() != null && entry.runningBalance().amount() != balance) {
                    mismatches++;
                    balance = entry.runningBalance().amount();
                }
                lines.add(line(entry, balance));
                continue;
            }
            balance += signed(entry);
            if (entry.direction() == TransactionDirection.CREDIT) {
                credits += entry.amount().amount();
            } else {
                debits += entry.amount().amount();
            }
            if (entry.runningBalance() != null && entry.runningBalance().amount() != balance) {
                mismatches++;
                balance = entry.runningBalance().amount();
            }
            lines.add(line(entry, balance));
        }

        StatementDocument document = new StatementDocument(accountId, currencyCode, customerName, msisdn,
                from, to, generatedAt, displayZone, opening, balance, credits, debits, lines);
        return new Result(document, mismatches);
    }

    private static long openingBalance(Long anchor, boolean historyBeforePeriod,
                                       List<TransactionEntry> chronological) {
        if (anchor != null) {
            return anchor;
        }
        if (!historyBeforePeriod) {
            return 0L;
        }
        // Pre-period history exists but its latest entry reported no balance:
        // back-derive from the first in-period entry that reports one.
        long delta = 0;
        for (TransactionEntry entry : chronological) {
            if (entry.reversed()) {
                continue;
            }
            if (!entry.balanceNeutral()) {
                delta += signed(entry);
            }
            if (entry.runningBalance() != null) {
                return entry.runningBalance().amount() - delta;
            }
        }
        throw new StatementUnavailableException(
                "The core reported no running balance on any reachable entry, so the opening "
                        + "balance cannot be established for this period.");
    }

    private static long signed(TransactionEntry entry) {
        long amount = entry.amount().amount();
        return entry.direction() == TransactionDirection.CREDIT ? amount : -amount;
    }

    private static StatementLine line(TransactionEntry entry, long balanceAfter) {
        return new StatementLine(entry.coreId(), entry.externalRef(), entry.valueDate(),
                entry.narrative(), entry.direction(), entry.amount().amount(), balanceAfter,
                entry.reversed(), entry.balanceNeutral());
    }
}
