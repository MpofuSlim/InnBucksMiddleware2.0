package zw.co.innbucks.middleware.statement.render;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.statement.StatementDocument;
import zw.co.innbucks.middleware.statement.StatementLine;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.CREDIT;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.DEBIT;

class CsvStatementRendererTest {

    private static StatementDocument document() {
        return new StatementDocument(
                "3f0d1c2e:wallet", "USD", "Tariro Moyo", "+263771234567",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("Africa/Harare"),
                10_000, 1_243_000, 1_239_000, 6_000,
                List.of(
                        new StatementLine("13", "37130855-ref", LocalDate.of(2026, 8, 3),
                                "Deposit", CREDIT, 1_239_000, 1_249_000, false, false),
                        new StatementLine("14", null, LocalDate.of(2026, 8, 5),
                                "Fee, monthly", DEBIT, 6_000, 1_243_000, false, false),
                        new StatementLine("15", null, LocalDate.of(2026, 8, 6),
                                "Waive Charge", DEBIT, 1_000, 1_243_000, false, true)));
    }

    @Test
    void rendersPreambleAndRfc4180Rows() {
        String csv = new String(CsvStatementRenderer.render(document()), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n", -1);

        assertThat(lines[0]).isEqualTo("InnBucks Account Statement");
        assertThat(csv).contains("Account,3f0d1c2e:wallet\n");
        assertThat(csv).contains("Currency,USD\n");
        assertThat(csv).contains("Period,2026-08-01 to 2026-08-31\n");
        // Money carries grouping commas, so it MUST arrive quoted or the
        // columns shear — this is the assertion that pins the quoting.
        assertThat(csv).contains("Opening balance,100.00\n");
        assertThat(csv).contains("Closing balance,\"12,430.00\"\n");
        assertThat(csv).contains(
                "Date,Description,Transaction ID,Reference,Direction,Amount,Balance,Reversed,Balance neutral\n");
        assertThat(csv).contains(
                "2026-08-03,Deposit,13,37130855-ref,CREDIT,\"12,390.00\",\"12,490.00\",false,false\n");
        // Narrative containing a comma is quoted; null reference is an empty field.
        assertThat(csv).contains(
                "2026-08-05,\"Fee, monthly\",14,,DEBIT,60.00,\"12,430.00\",false,false\n");
        // A waived charge: amount shown, balance unchanged, neutral column true.
        assertThat(csv).contains(
                "2026-08-06,Waive Charge,15,,DEBIT,10.00,\"12,430.00\",false,true\n");
    }

    @Test
    void generatedTimestampRendersInTheCivilZone() {
        String csv = new String(CsvStatementRenderer.render(document()), StandardCharsets.UTF_8);
        // 10:00Z is 12:00 in Harare (CAT, UTC+2, no DST).
        assertThat(csv).contains("Generated,2026-09-07 12:00 CAT\n");
    }
}
