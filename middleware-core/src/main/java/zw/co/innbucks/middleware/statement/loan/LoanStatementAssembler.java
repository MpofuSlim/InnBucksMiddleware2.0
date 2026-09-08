package zw.co.innbucks.middleware.statement.loan;

import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns core loan transactions into a balanced {@link LoanStatementDocument}.
 * Pure — no Spring, no I/O — so every rule below is pinned by a unit test that
 * needs nothing but this class.
 *
 * <p><b>Balance policy — the same one the deposit statement runs, applied to
 * the principal outstanding:</b>
 * <ol>
 *   <li><b>Opening principal</b> is the core's own principal-after on the
 *       latest entry BEFORE the period (the caller's anchor, walked past
 *       reversed and balance-less rows with their effects added back).</li>
 *   <li>No history before the period — including "the whole life of the
 *       loan", the default request — means an opening of ZERO, which is a
 *       fact: nothing had been lent yet.</li>
 *   <li>History exists but nothing probed carries a balance: back-derive from
 *       the first in-period entry that does. Nothing in reach carries one:
 *       REFUSE ({@link StatementUnavailableException}) rather than invent.</li>
 *   <li><b>Per line</b> the balance moves by the entry's own principal effect
 *       ({@code principalDeltaMinor}, derived by the adapter from the core's
 *       type and portions); the core's reported balance-after wins over that
 *       arithmetic, and each disagreement is counted for the caller.</li>
 *   <li><b>Reversed rows</b> are shown, move nothing, feed no total, and are
 *       never an anchor. <b>{@code NONE} rows</b> (accruals, markers,
 *       charge-off classifications) likewise move nothing and total
 *       nothing; their balance-after, when present, is simply the unchanged
 *       balance and gets the same core-wins check.</li>
 * </ol>
 */
final class LoanStatementAssembler {

    private LoanStatementAssembler() {
    }

    /** The assembled document plus how often the core's balances disagreed with its portions. */
    record Result(LoanStatementDocument document, int balanceMismatches) {
    }

    /**
     * @param openingAnchorMinor  principal-after of the latest pre-period
     *                            entry, when the core reported one; null otherwise
     * @param historyBeforePeriod whether ANY entry exists before the period
     * @param chronological       the period's entries OLDEST FIRST
     */
    static Result assemble(OperatorLoanView loan, LocalDate from, LocalDate to,
                           Instant generatedAt, ZoneId displayZone,
                           Long openingAnchorMinor, boolean historyBeforePeriod,
                           List<LoanTransactionEntry> chronological) {

        long opening = openingPrincipal(openingAnchorMinor, historyBeforePeriod, chronological);

        List<LoanStatementLine> lines = new ArrayList<>(chronological.size());
        long balance = opening;
        long disbursed = 0;
        long repaid = 0;
        long principalRepaid = 0;
        long interestRepaid = 0;
        long feesRepaid = 0;
        long penaltiesRepaid = 0;
        long waived = 0;
        long writtenOff = 0;
        int mismatches = 0;

        for (LoanTransactionEntry entry : chronological) {
            if (entry.reversed()) {
                lines.add(line(entry, balance));
                continue;
            }
            if (entry.kind() != LoanEntryKind.NONE) {
                balance += entry.principalDeltaMinor();
                long amount = entry.amount().amount();
                switch (entry.kind()) {
                    case DISBURSEMENT -> disbursed += amount;
                    case REPAYMENT -> {
                        repaid += amount;
                        principalRepaid += minor(entry.principalPortion());
                        interestRepaid += minor(entry.interestPortion());
                        feesRepaid += minor(entry.feePortion());
                        penaltiesRepaid += minor(entry.penaltyPortion());
                    }
                    case WAIVER -> waived += amount;
                    case WRITE_OFF -> writtenOff += amount;
                    default -> {
                        // OTHER: walked the balance above, totals nothing.
                    }
                }
            }
            if (entry.principalBalanceAfter() != null && entry.principalBalanceAfter() != balance) {
                mismatches++;
                balance = entry.principalBalanceAfter();
            }
            lines.add(line(entry, balance));
        }

        LoanStatementDocument document = new LoanStatementDocument(
                loan.loanId(), loan.accountNumber(), loan.externalId(), loan.currencyCode(),
                loan.borrowerName(), loan.borrowerMobile(), loan.productName(), loan.status(),
                loan.principal() == null ? null : loan.principal().amount(),
                loan.annualInterestRate(), loan.termDescription(), loan.disbursedOn(), loan.maturityDate(),
                from, to, generatedAt, displayZone,
                opening, balance,
                new LoanStatementDocument.Totals(disbursed, repaid, principalRepaid, interestRepaid,
                        feesRepaid, penaltiesRepaid, waived, writtenOff),
                loan.position(), lines);
        return new Result(document, mismatches);
    }

    private static long openingPrincipal(Long anchor, boolean historyBeforePeriod,
                                         List<LoanTransactionEntry> chronological) {
        if (anchor != null) {
            return anchor;
        }
        if (!historyBeforePeriod) {
            return 0L;
        }
        long delta = 0;
        for (LoanTransactionEntry entry : chronological) {
            if (entry.reversed()) {
                continue;
            }
            delta += entry.principalDeltaMinor();
            if (entry.principalBalanceAfter() != null) {
                return entry.principalBalanceAfter() - delta;
            }
        }
        throw new StatementUnavailableException(
                "The core reported no principal balance on any reachable entry, so the opening "
                        + "principal cannot be established for this period.");
    }

    private static long minor(MinorUnits units) {
        return units == null ? 0 : units.amount();
    }

    private static Long minorOrNull(MinorUnits units) {
        return units == null ? null : units.amount();
    }

    private static LoanStatementLine line(LoanTransactionEntry entry, long balanceAfter) {
        return new LoanStatementLine(entry.coreId(), entry.externalRef(), entry.valueDate(),
                entry.narrative(), entry.kind(), entry.amount().amount(),
                minorOrNull(entry.principalPortion()), minorOrNull(entry.interestPortion()),
                minorOrNull(entry.feePortion()), minorOrNull(entry.penaltyPortion()),
                entry.principalDeltaMinor(), balanceAfter, entry.reversed());
    }
}
