package zw.co.innbucks.middleware.statement.render;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.CREDIT;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.DEBIT;

/**
 * Asserts against the TEXT extracted from the rendered bytes, not just that
 * bytes exist — a renderer that produced a well-formed but empty PDF would
 * pass a magic-number check and fail a customer.
 */
class PdfStatementRendererTest {

    private static StatementDocument document(List<StatementLine> lines) {
        return document(DepositAccountKind.SAVINGS, lines);
    }

    private static StatementDocument document(DepositAccountKind kind, List<StatementLine> lines) {
        return new StatementDocument(
                "3f0d1c2e:wallet", kind, "USD", "Tariro Moyo", "+263771234567",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("Africa/Harare"),
                10_000, 14_000, 5_000, 1_000, lines);
    }

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
    void rendersHeaderSummaryAndLines() throws IOException {
        byte[] pdf = PdfStatementRenderer.render(document(List.of(
                new StatementLine("13", "ref-1", LocalDate.of(2026, 8, 3),
                        "Deposit", CREDIT, 5_000, 15_000, false, false),
                new StatementLine("14", null, LocalDate.of(2026, 8, 5),
                        "Withdrawal", DEBIT, 1_000, 14_000, false, false))));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("Account Statement");
        assertThat(text).contains("Tariro Moyo");
        assertThat(text).contains("+263771234567");
        assertThat(text).contains("3f0d1c2e:wallet");
        assertThat(text).contains("01 Aug 2026 to 31 Aug 2026");
        // 10:00Z rendered in the civil zone, UTC+2.
        assertThat(text).contains("07 Sep 2026, 12:00");
        assertThat(text).contains("Opening balance");
        assertThat(text).contains("100.00");
        assertThat(text).contains("Closing balance");
        assertThat(text).contains("140.00");
        assertThat(text).contains("Deposit");
        assertThat(text).contains("Withdrawal");
        assertThat(text).contains("150.00");
    }

    @Test
    void balanceNeutralLinesAreMarked() throws IOException {
        byte[] pdf = PdfStatementRenderer.render(document(List.of(
                new StatementLine("15", null, LocalDate.of(2026, 8, 6),
                        "Waive Charge", DEBIT, 1_000, 10_000, false, true))));

        assertThat(textOf(pdf)).contains("Waive Charge (no balance effect)");
    }

    @Test
    void reversedLinesAreMarked() throws IOException {
        byte[] pdf = PdfStatementRenderer.render(document(List.of(
                new StatementLine("13", null, LocalDate.of(2026, 8, 3),
                        "Deposit", CREDIT, 5_000, 10_000, true, false))));

        assertThat(textOf(pdf)).contains("Deposit (reversed)");
    }

    /**
     * A fixed deposit statements exactly like a savings account — same
     * balances, same lines — and must NOT be titled as one: a customer hands
     * this to a bank that reads the heading.
     */
    @Test
    void fixedDepositIsTitledAsOne() throws IOException {
        String text = textOf(PdfStatementRenderer.render(document(DepositAccountKind.FIXED_DEPOSIT, List.of(
                new StatementLine("13", null, LocalDate.of(2026, 8, 3),
                        "Deposit", CREDIT, 5_000, 15_000, false, false)))));

        assertThat(text).contains("Fixed Deposit Statement");
        assertThat(text).doesNotContain("Account Statement");
        assertThat(text).contains("Fixed Deposit");
        // The seventh meta pair must survive the four-column grid.
        assertThat(text).contains("07 Sep 2026, 12:00");
    }

    @Test
    void emptyPeriodSaysSoInsteadOfRenderingABareGrid() throws IOException {
        byte[] pdf = PdfStatementRenderer.render(document(List.of()));

        assertThat(textOf(pdf)).contains("No transactions in this period.");
    }

    @Test
    void missingCustomerNameRendersAPlaceholderNotAnError() throws IOException {
        StatementDocument nameless = new StatementDocument(
                "3f0d1c2e:wallet", DepositAccountKind.SAVINGS, "USD", null, "+263771234567",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("Africa/Harare"),
                0, 0, 0, 0, List.of());

        assertThat(textOf(PdfStatementRenderer.render(nameless))).contains("+263771234567");
    }
}
