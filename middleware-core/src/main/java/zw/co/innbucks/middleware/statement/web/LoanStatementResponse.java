package zw.co.innbucks.middleware.statement.web;

import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.loan.LoanStatementLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The JSON rendering of a {@link LoanStatementDocument} — same numbers as the
 * PDF and CSV renderings by construction, because all three are produced
 * from the one assembled document.
 */
@Schema(description = "A loan statement: the loan's terms, the principal balance walked across the "
        + "period, period totals, the position as at generation, and every transaction with its "
        + "principal / interest / fee / penalty split. Also available as PDF or CSV via ?format=.")
public record LoanStatementResponse(

        @Schema(description = "The core's loan id — the number in the console URL.", example = "9")
        String loanId,

        @Schema(description = "The loan account number as printed on documents.", example = "000000009")
        String accountNumber,

        @Schema(description = "The loan's external reference in the core, if any.",
                example = "biz-loan-77", nullable = true)
        String externalId,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "Borrower display name from the core; null when it has none.",
                example = "Shumba Traders", nullable = true)
        String borrowerName,

        @Schema(description = "Borrower's mobile exactly as the core stores it — display only, may be "
                + "unnormalised or null.", example = "0771234567", nullable = true)
        String borrowerMobile,

        @Schema(example = "SME Working Capital", nullable = true)
        String productName,

        @Schema(description = "The core's own status label. Show it; do not parse it.", example = "Active")
        String status,

        @Schema(description = "Contracted principal, MINOR units; null before approval.",
                example = "500000", nullable = true)
        Long principalMinor,

        @Schema(description = "Nominal annual interest rate in percent.", example = "24.00", nullable = true)
        BigDecimal annualInterestRate,

        @Schema(description = "Human-readable term.", example = "12 monthly repayments", nullable = true)
        String term,

        @Schema(description = "Actual disbursement date; null before disbursement.", nullable = true)
        LocalDate disbursedOn,

        @Schema(description = "Expected maturity date.", nullable = true)
        LocalDate maturityDate,

        @Schema(description = "Inclusive period start. NULL means the whole life of the loan — the "
                + "default when the request carried no from.", example = "2026-08-01", nullable = true)
        LocalDate from,

        @Schema(description = "Inclusive period end — today when the request carried no to.",
                example = "2026-08-31")
        LocalDate to,

        @Schema(description = "When this document was assembled (UTC instant).")
        Instant generatedAt,

        @Schema(description = "Principal outstanding before the first entry of the period, MINOR units, "
                + "SIGNED. Anchored on the core's own per-transaction balance; zero when the period "
                + "starts before anything was lent.", example = "0")
        long openingPrincipalMinor,

        @Schema(description = "Principal outstanding after the last entry of the period, MINOR units, "
                + "SIGNED. openingPrincipalMinor + Σ lines[].principalDeltaMinor always equals this.",
                example = "375000")
        long closingPrincipalMinor,

        Totals totals,

        @Schema(description = "What the borrower owes NOW (at generatedAt), from the core's own summary "
                + "— not at the period end. Null for a loan that has not been disbursed.",
                nullable = true)
        Position position,

        List<LoanStatementLineView> lines
) {

    @Schema(description = "Period totals, MINOR units, over non-reversed lines only.")
    public record Totals(
            @Schema(description = "Sum of disbursement amounts.", example = "500000")
            long disbursedMinor,
            @Schema(description = "Full amount of every repayment-shaped line.", example = "47500")
            long repaidMinor,
            @Schema(description = "The principal portion of those repayments — the part that reduced "
                    + "closingPrincipalMinor.", example = "41667")
            long principalRepaidMinor,
            @Schema(example = "5500") long interestRepaidMinor,
            @Schema(example = "333") long feesRepaidMinor,
            @Schema(example = "0") long penaltiesRepaidMinor,
            @Schema(description = "Interest and charges forgiven in the period.", example = "1000")
            long waivedMinor,
            @Schema(example = "0") long writtenOffMinor
    ) {
    }

    @Schema(description = "The core's view of what is outstanding right now, MINOR units, SIGNED.")
    public record Position(
            @Schema(example = "375000") long principalOutstandingMinor,
            @Schema(example = "38000") long interestOutstandingMinor,
            @Schema(example = "0") long feesOutstandingMinor,
            @Schema(example = "1500") long penaltiesOutstandingMinor,
            @Schema(example = "414500") long totalOutstandingMinor,
            @Schema(example = "48667") long totalOverdueMinor,
            @Schema(description = "When arrears began; null when nothing is overdue.", nullable = true)
            LocalDate overdueSince
    ) {
    }

    public static LoanStatementResponse of(LoanStatementDocument d) {
        LoanStatementDocument.Totals t = d.totals();
        OperatorLoanView.LoanPosition p = d.position();
        return new LoanStatementResponse(
                d.loanId(), d.accountNumber(), d.externalId(), d.currencyCode(),
                d.borrowerName(), d.borrowerMobile(), d.productName(), d.status(),
                d.principalMinor(), d.annualInterestRate(), d.termDescription(),
                d.disbursedOn(), d.maturityDate(), d.from(), d.to(), d.generatedAt(),
                d.openingPrincipalMinor(), d.closingPrincipalMinor(),
                new Totals(t.disbursedMinor(), t.repaidMinor(), t.principalRepaidMinor(),
                        t.interestRepaidMinor(), t.feesRepaidMinor(), t.penaltiesRepaidMinor(),
                        t.waivedMinor(), t.writtenOffMinor()),
                p == null ? null : new Position(p.principalOutstandingMinor(), p.interestOutstandingMinor(),
                        p.feesOutstandingMinor(), p.penaltiesOutstandingMinor(), p.totalOutstandingMinor(),
                        p.totalOverdueMinor(), p.overdueSince()),
                d.lines().stream().map(LoanStatementResponse::line).toList());
    }

    private static LoanStatementLineView line(LoanStatementLine l) {
        return new LoanStatementLineView(l.coreId(), l.reference(), l.date(), l.narrative(),
                l.kind().name(), l.amountMinor(), l.principalMinor(), l.interestMinor(),
                l.feesMinor(), l.penaltiesMinor(), l.principalDeltaMinor(),
                l.principalBalanceAfterMinor(), l.reversed());
    }
}
