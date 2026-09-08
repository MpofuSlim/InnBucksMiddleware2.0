package zw.co.innbucks.middleware.statement.render;

import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementMoney;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.loan.LoanStatementLine;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link LoanStatementDocument} as CSV — a metadata preamble, a
 * blank line, then RFC-4180 rows. Same money rendering and quoting rules as
 * the deposit CSV, same formatting-only discipline: every number was computed
 * by the assembler or reported by the core.
 */
public final class LoanCsvStatementRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz", Locale.ENGLISH);

    private LoanCsvStatementRenderer() {
    }

    public static byte[] render(LoanStatementDocument s) {
        StringBuilder out = new StringBuilder(512 + s.lines().size() * 96);
        String ccy = s.currencyCode();
        LoanStatementDocument.Totals t = s.totals();

        row(out, "InnBucks " + s.title());
        row(out, "Loan account", s.accountNumber());
        row(out, "Loan ID", s.loanId());
        row(out, "Reference", blank(s.externalId()));
        row(out, "Borrower", blank(s.borrowerName()));
        row(out, "Mobile", blank(s.borrowerMobile()));
        row(out, "Product", blank(s.productName()));
        row(out, "Status", s.status());
        row(out, "Currency", ccy);
        row(out, "Principal", s.principalMinor() == null ? "" : StatementMoney.format(s.principalMinor(), ccy));
        row(out, "Interest rate (% p.a.)", s.annualInterestRate() == null ? ""
                : s.annualInterestRate().setScale(2, RoundingMode.HALF_UP).toPlainString());
        row(out, "Term", blank(s.termDescription()));
        row(out, "Disbursed on", s.disbursedOn() == null ? "" : DATE.format(s.disbursedOn()));
        row(out, "Maturity", s.maturityDate() == null ? "" : DATE.format(s.maturityDate()));
        row(out, "Period", (s.from() == null ? "Since inception" : DATE.format(s.from()))
                + " to " + DATE.format(s.to()));
        row(out, "Generated", GENERATED.format(s.generatedAt().atZone(s.displayZone())));
        row(out, "Opening principal", StatementMoney.format(s.openingPrincipalMinor(), ccy));
        row(out, "Disbursed", StatementMoney.format(t.disbursedMinor(), ccy));
        row(out, "Total repaid", StatementMoney.format(t.repaidMinor(), ccy));
        row(out, "Principal repaid", StatementMoney.format(t.principalRepaidMinor(), ccy));
        row(out, "Interest paid", StatementMoney.format(t.interestRepaidMinor(), ccy));
        row(out, "Fees paid", StatementMoney.format(t.feesRepaidMinor(), ccy));
        row(out, "Penalties paid", StatementMoney.format(t.penaltiesRepaidMinor(), ccy));
        row(out, "Waived", StatementMoney.format(t.waivedMinor(), ccy));
        row(out, "Written off", StatementMoney.format(t.writtenOffMinor(), ccy));
        row(out, "Closing principal", StatementMoney.format(s.closingPrincipalMinor(), ccy));
        if (s.position() != null) {
            OperatorLoanView.LoanPosition p = s.position();
            row(out, "Principal outstanding now", StatementMoney.format(p.principalOutstandingMinor(), ccy));
            row(out, "Interest outstanding now", StatementMoney.format(p.interestOutstandingMinor(), ccy));
            row(out, "Fees outstanding now", StatementMoney.format(p.feesOutstandingMinor(), ccy));
            row(out, "Penalties outstanding now", StatementMoney.format(p.penaltiesOutstandingMinor(), ccy));
            row(out, "Total outstanding now", StatementMoney.format(p.totalOutstandingMinor(), ccy));
            row(out, "Overdue now", StatementMoney.format(p.totalOverdueMinor(), ccy));
            row(out, "Overdue since", p.overdueSince() == null ? "" : DATE.format(p.overdueSince()));
        }
        out.append('\n');

        row(out, "Date", "Description", "Transaction ID", "Reference", "Kind", "Amount", "Principal",
                "Interest", "Fees", "Penalties", "Principal effect", "Principal balance", "Reversed");
        for (LoanStatementLine line : s.lines()) {
            row(out,
                    DATE.format(line.date()),
                    blank(line.narrative()),
                    line.coreId(),
                    blank(line.reference()),
                    line.kind().name(),
                    StatementMoney.format(line.amountMinor(), ccy),
                    portion(line.principalMinor(), ccy),
                    portion(line.interestMinor(), ccy),
                    portion(line.feesMinor(), ccy),
                    portion(line.penaltiesMinor(), ccy),
                    StatementMoney.format(line.principalDeltaMinor(), ccy),
                    StatementMoney.format(line.principalBalanceAfterMinor(), ccy),
                    Boolean.toString(line.reversed()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String portion(Long minor, String ccy) {
        return minor == null ? "" : StatementMoney.format(minor, ccy);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static void row(StringBuilder out, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(fields[i]));
        }
        out.append('\n');
    }

    /** RFC-4180: quote when the field contains a comma, quote, or newline; double embedded quotes. */
    private static String escape(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0
                && field.indexOf('\n') < 0 && field.indexOf('\r') < 0) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }
}
