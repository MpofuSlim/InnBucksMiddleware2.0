package zw.co.innbucks.middleware.statement.render;

import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementLine;
import zw.co.innbucks.middleware.statement.StatementMoney;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders a {@link StatementDocument} as CSV — a metadata preamble, a blank
 * line, then RFC-4180 rows. Money renders in major units with grouping
 * ({@code 1,234.56}), same as the PDF; the embedded comma is exactly why
 * every field goes through the RFC-4180 quoting below rather than naive
 * joining.
 *
 * <p>Formatting only, like the PDF renderer: every number was computed by the
 * assembler.
 */
public final class CsvStatementRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter GENERATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz", Locale.ENGLISH);

    private CsvStatementRenderer() {
    }

    public static byte[] render(StatementDocument s) {
        StringBuilder out = new StringBuilder(256 + s.lines().size() * 64);
        String ccy = s.currencyCode();

        row(out, "InnBucks Account Statement");
        row(out, "Account", s.accountId());
        row(out, "Customer", s.customerName() == null ? "" : s.customerName());
        row(out, "Mobile", s.msisdn() == null ? "" : s.msisdn());
        row(out, "Currency", ccy);
        row(out, "Period", DATE.format(s.from()) + " to " + DATE.format(s.to()));
        row(out, "Generated", GENERATED.format(s.generatedAt().atZone(s.displayZone())));
        row(out, "Opening balance", StatementMoney.format(s.openingBalanceMinor(), ccy));
        row(out, "Money in", StatementMoney.format(s.totalCreditsMinor(), ccy));
        row(out, "Money out", StatementMoney.format(s.totalDebitsMinor(), ccy));
        row(out, "Closing balance", StatementMoney.format(s.closingBalanceMinor(), ccy));
        out.append('\n');

        row(out, "Date", "Description", "Transaction ID", "Reference", "Direction", "Amount", "Balance",
                "Reversed", "Balance neutral");
        for (StatementLine line : s.lines()) {
            row(out,
                    DATE.format(line.date()),
                    line.narrative() == null ? "" : line.narrative(),
                    line.coreId(),
                    line.reference() == null ? "" : line.reference(),
                    line.direction().name(),
                    StatementMoney.format(line.amountMinor(), ccy),
                    StatementMoney.format(line.balanceAfterMinor(), ccy),
                    Boolean.toString(line.reversed()),
                    Boolean.toString(line.balanceNeutral()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
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
