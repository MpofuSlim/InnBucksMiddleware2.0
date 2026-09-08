package zw.co.innbucks.middleware.statement.loan;

import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * A complete LOAN statement — the renderer-neutral model every output format
 * (JSON, PDF, CSV) is produced from, so the three cannot disagree about a
 * number. The loan counterpart of {@code StatementDocument}, and deliberately
 * a separate type: a loan is not an account with a running balance but an
 * obligation with four moving parts (principal, interest, fees, penalties),
 * and forcing it into the deposit-statement shape would either hide three of
 * them or lie about the fourth.
 *
 * <p><b>What the balance column means.</b> {@code openingPrincipalMinor} →
 * {@code closingPrincipalMinor} walk the PRINCIPAL outstanding — the figure
 * every core tracks per transaction and the one a credit manager reads first.
 * {@code opening + Σ principalDelta == closing} holds whenever the core's own
 * per-transaction balances agree with its portions; where they do not, the
 * assembler adopts the core's balance (the core is the book of record) and
 * counts the disagreement for the caller.
 *
 * <p><b>What the position means.</b> {@code position} is what the borrower
 * owes NOW — at generation time, from the core's own summary — not at the
 * period end. It is the answer to "where does this loan stand", rendered as
 * exactly that, and it is null for a loan that has not been disbursed.
 *
 * @param from             inclusive period start; NULL means the whole life of
 *                         the loan, which is the common request ("the loan
 *                         statement") and why loans have no period ceiling
 * @param principalMinor   the contracted principal; null before approval
 * @param borrowerMobile   as the CORE stores it — display-only, may be
 *                         unnormalised or null
 */
public record LoanStatementDocument(
        String loanId,
        String accountNumber,
        String externalId,
        String currencyCode,
        String borrowerName,
        String borrowerMobile,
        String productName,
        String status,
        Long principalMinor,
        BigDecimal annualInterestRate,
        String termDescription,
        LocalDate disbursedOn,
        LocalDate maturityDate,
        LocalDate from,
        LocalDate to,
        Instant generatedAt,
        ZoneId displayZone,
        long openingPrincipalMinor,
        long closingPrincipalMinor,
        Totals totals,
        OperatorLoanView.LoanPosition position,
        List<LoanStatementLine> lines
) {

    public LoanStatementDocument {
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(displayZone, "displayZone");
        Objects.requireNonNull(totals, "totals");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (from != null && to.isBefore(from)) {
            throw new IllegalArgumentException("'to' (" + to + ") is before 'from' (" + from + ")");
        }
    }

    /**
     * Period totals, non-negative, over non-reversed lines only. A line feeds
     * exactly the total its kind names; {@code OTHER} and {@code NONE} lines
     * feed none (they still walk the balance where they have an effect).
     *
     * @param repaidMinor          the full amount of every repayment-shaped line
     * @param principalRepaidMinor the principal PORTION of those — the part
     *                             that moved the balance
     */
    public record Totals(
            long disbursedMinor,
            long repaidMinor,
            long principalRepaidMinor,
            long interestRepaidMinor,
            long feesRepaidMinor,
            long penaltiesRepaidMinor,
            long waivedMinor,
            long writtenOffMinor
    ) {

        public Totals {
            for (long total : new long[] {disbursedMinor, repaidMinor, principalRepaidMinor,
                    interestRepaidMinor, feesRepaidMinor, penaltiesRepaidMinor, waivedMinor,
                    writtenOffMinor}) {
                if (total < 0) {
                    throw new IllegalArgumentException("totals must be >= 0");
                }
            }
        }
    }

    public String title() {
        return "Loan Statement";
    }
}
