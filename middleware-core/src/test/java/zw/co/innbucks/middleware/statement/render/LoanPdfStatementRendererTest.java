package zw.co.innbucks.middleware.statement.render;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.loan.LoanStatementDocument;
import zw.co.innbucks.middleware.statement.loan.LoanStatementLine;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts against the TEXT extracted from the rendered bytes, not just that
 * bytes exist — a renderer that produced a well-formed but empty PDF would
 * pass a magic-number check and fail a credit manager.
 */
class LoanPdfStatementRendererTest {

    static LoanStatementDocument document(LocalDate from, OperatorLoanView.LoanPosition position,
                                          List<LoanStatementLine> lines) {
        return new LoanStatementDocument(
                "9", "000000009", "biz-loan-77", "USD", "Shumba Traders", "0771234567",
                "SME Working Capital", "Active", 500_000L, new BigDecimal("24.000000"),
                "12 monthly repayments", LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1),
                from, LocalDate.of(2026, 8, 31),
                Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("Africa/Harare"),
                500_000, 458_333,
                new LoanStatementDocument.Totals(0, 47_500, 41_667, 5_500, 333, 0, 1_000, 0),
                position, lines);
    }

    static final OperatorLoanView.LoanPosition POSITION = new OperatorLoanView.LoanPosition(
            375_000, 38_000, 0, 1_500, 414_500, 48_667, LocalDate.of(2026, 8, 1));

    static final LoanStatementLine REPAYMENT = new LoanStatementLine("902", "rcpt-4411",
            LocalDate.of(2026, 8, 1), "Repayment", LoanEntryKind.REPAYMENT, 47_500,
            41_667L, 5_500L, 333L, 0L, -41_667, 458_333, false);

    private static String textOf(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder text = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void rendersTermsTotalsPositionAndLines() throws IOException {
        byte[] pdf = LoanPdfStatementRenderer.render(document(LocalDate.of(2026, 8, 1), POSITION, List.of(
                REPAYMENT,
                new LoanStatementLine("903", null, LocalDate.of(2026, 8, 5), "Waive interest",
                        LoanEntryKind.WAIVER, 1_000, 0L, 1_000L, 0L, 0L, 0, 458_333, false))));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("Loan Statement");
        assertThat(text).contains("Shumba Traders");
        assertThat(text).contains("0771234567");
        assertThat(text).contains("000000009");
        assertThat(text).contains("SME Working Capital");
        assertThat(text).contains("24.00% p.a.");
        assertThat(text).contains("12 monthly repayments");
        assertThat(text).contains("01 Mar 2026");
        assertThat(text).contains("01 Aug 2026 to 31 Aug 2026");
        assertThat(text).contains("Opening principal");
        assertThat(text).contains("5,000.00");
        assertThat(text).contains("Closing principal");
        assertThat(text).contains("4,583.33");
        assertThat(text).contains("Principal repaid");
        assertThat(text).contains("416.67");
        assertThat(text).contains("Position as at 07 Sep 2026, 12:00");
        assertThat(text).contains("Principal outstanding");
        assertThat(text).contains("3,750.00");
        assertThat(text).contains("486.67");
        assertThat(text).contains("since 01 Aug 2026");
        assertThat(text).contains("Repayment");
        assertThat(text).contains("475.00");
        assertThat(text).contains("55.00");
        assertThat(text).contains("Waive interest");
    }

    @Test
    void lifeOfLoanSaysSinceInceptionAndAnUndisbursedLoanHasNoPositionBlock() throws IOException {
        String text = textOf(LoanPdfStatementRenderer.render(document(null, null, List.of())));

        assertThat(text).contains("Since inception to 31 Aug 2026");
        assertThat(text).doesNotContain("Position as at");
        assertThat(text).contains("No transactions in this period.");
    }

    @Test
    void reversedAndBookkeepingLinesAreMarked() throws IOException {
        String text = textOf(LoanPdfStatementRenderer.render(document(LocalDate.of(2026, 8, 1), POSITION, List.of(
                new LoanStatementLine("904", null, LocalDate.of(2026, 8, 10), "Repayment",
                        LoanEntryKind.REPAYMENT, 47_500, null, null, null, null, 0, 458_333, true),
                new LoanStatementLine("905", null, LocalDate.of(2026, 8, 20), "Charge-off",
                        LoanEntryKind.NONE, 458_333, null, null, null, null, 0, 458_333, false)))));

        assertThat(text).contains("Repayment (reversed)");
        assertThat(text).contains("Charge-off (no balance effect)");
    }
}
