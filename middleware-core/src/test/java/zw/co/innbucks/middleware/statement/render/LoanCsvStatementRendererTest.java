package zw.co.innbucks.middleware.statement.render;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanCsvStatementRendererTest {

    @Test
    void rendersPreambleTotalsPositionAndRfc4180Rows() {
        String csv = new String(LoanCsvStatementRenderer.render(LoanPdfStatementRendererTest.document(
                LocalDate.of(2026, 8, 1), LoanPdfStatementRendererTest.POSITION,
                List.of(LoanPdfStatementRendererTest.REPAYMENT))), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("InnBucks Loan Statement\n");
        assertThat(csv).contains("Loan account,000000009\n");
        assertThat(csv).contains("Loan ID,9\n");
        assertThat(csv).contains("Reference,biz-loan-77\n");
        assertThat(csv).contains("Borrower,Shumba Traders\n");
        assertThat(csv).contains("Product,SME Working Capital\n");
        assertThat(csv).contains("Interest rate (% p.a.),24.00\n");
        assertThat(csv).contains("Term,12 monthly repayments\n");
        assertThat(csv).contains("Disbursed on,2026-03-01\n");
        assertThat(csv).contains("Period,2026-08-01 to 2026-08-31\n");
        assertThat(csv).contains("Generated,2026-09-07 12:00 CAT\n");
        // Grouped money MUST arrive quoted or the columns shear.
        assertThat(csv).contains("Opening principal,\"5,000.00\"\n");
        assertThat(csv).contains("Principal repaid,416.67\n");
        assertThat(csv).contains("Closing principal,\"4,583.33\"\n");
        assertThat(csv).contains("Principal outstanding now,\"3,750.00\"\n");
        assertThat(csv).contains("Overdue since,2026-08-01\n");
        assertThat(csv).contains("Date,Description,Transaction ID,Reference,Kind,Amount,Principal,Interest,"
                + "Fees,Penalties,Principal effect,Principal balance,Reversed\n");
        assertThat(csv).contains("2026-08-01,Repayment,902,rcpt-4411,REPAYMENT,475.00,416.67,55.00,3.33,0.00,"
                + "-416.67,\"4,583.33\",false\n");
    }

    @Test
    void lifeOfLoanAndNoPositionRenderHonestly() {
        String csv = new String(LoanCsvStatementRenderer.render(LoanPdfStatementRendererTest.document(
                null, null, List.of())), StandardCharsets.UTF_8);

        assertThat(csv).contains("Period,Since inception to 2026-08-31\n");
        assertThat(csv).doesNotContain("outstanding now");
    }
}
