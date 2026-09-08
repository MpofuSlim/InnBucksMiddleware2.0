package zw.co.innbucks.middleware.statement.loan;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.DISBURSEMENT;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.NONE;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.OTHER;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.REPAYMENT;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.WAIVER;
import static zw.co.innbucks.middleware.corebanking.value.LoanEntryKind.WRITE_OFF;

/**
 * Pins the principal-balance policy: the core's per-transaction balance
 * anchors and wins; the adapter's principal effect fills its silences; every
 * kind feeds exactly the total it names; a statement with no honest anchor is
 * refused, never invented.
 */
class LoanStatementAssemblerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);
    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

    static final OperatorLoanView LOAN = new OperatorLoanView(
            "9", "000000009", "biz-loan-77", "USD", "SME Working Capital", "Active", true,
            new MinorUnits(500_000, "USD"), new BigDecimal("24.00"), "12 monthly repayments",
            LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1), "Shumba Traders", "0771234567",
            new OperatorLoanView.LoanPosition(375_000, 38_000, 0, 1_500, 414_500, 48_667,
                    LocalDate.of(2026, 8, 1)));

    private static int nextId = 900;

    private static MinorUnits usd(long minor) {
        return new MinorUnits(minor, "USD");
    }

    static LoanTransactionEntry disbursement(long amount, Long balanceAfter) {
        return new LoanTransactionEntry(String.valueOf(nextId++), null, FROM.plusDays(1), "Disbursement",
                DISBURSEMENT, usd(amount), null, null, null, null, amount, balanceAfter, false);
    }

    static LoanTransactionEntry repayment(long amount, long principal, long interest, long fees,
                                          long penalties, Long balanceAfter) {
        return new LoanTransactionEntry(String.valueOf(nextId++), "rcpt-" + nextId, FROM.plusDays(5),
                "Repayment", REPAYMENT, usd(amount), usd(principal), usd(interest), usd(fees),
                usd(penalties), -principal, balanceAfter, false);
    }

    static LoanTransactionEntry entry(LoanEntryKind kind, long amount, Long principalPortion,
                                      long delta, Long balanceAfter, boolean reversed) {
        return new LoanTransactionEntry(String.valueOf(nextId++), null, FROM.plusDays(9), kind.name(),
                kind, usd(amount), principalPortion == null ? null : usd(principalPortion),
                null, null, null, reversed ? 0 : delta, balanceAfter, reversed);
    }

    private static LoanStatementAssembler.Result assemble(Long anchor, boolean historyBefore,
                                                          List<LoanTransactionEntry> chronological) {
        return LoanStatementAssembler.assemble(LOAN, FROM, TO,
                Instant.parse("2026-09-07T10:00:00Z"), HARARE, anchor, historyBefore, chronological);
    }

    @Test
    void anchoredOpeningWalksThePrincipalAndTotalsEveryKindWhereItBelongs() {
        LoanStatementAssembler.Result result = assemble(500_000L, true, List.of(
                repayment(47_500, 41_667, 5_500, 333, 0, 458_333L),
                entry(WAIVER, 1_000, 0L, 0, 458_333L, false),
                entry(NONE, 458_333, null, 0, 458_333L, false)));

        LoanStatementDocument doc = result.document();
        assertThat(doc.openingPrincipalMinor()).isEqualTo(500_000);
        assertThat(doc.lines()).extracting(LoanStatementLine::principalBalanceAfterMinor)
                .containsExactly(458_333L, 458_333L, 458_333L);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(458_333);
        LoanStatementDocument.Totals t = doc.totals();
        assertThat(t.disbursedMinor()).isZero();
        assertThat(t.repaidMinor()).isEqualTo(47_500);
        assertThat(t.principalRepaidMinor()).isEqualTo(41_667);
        assertThat(t.interestRepaidMinor()).isEqualTo(5_500);
        assertThat(t.feesRepaidMinor()).isEqualTo(333);
        assertThat(t.penaltiesRepaidMinor()).isZero();
        assertThat(t.waivedMinor()).isEqualTo(1_000);
        assertThat(t.writtenOffMinor()).isZero();
        assertThat(result.balanceMismatches()).isZero();
        // Header material is the core's, verbatim.
        assertThat(doc.accountNumber()).isEqualTo("000000009");
        assertThat(doc.borrowerName()).isEqualTo("Shumba Traders");
        assertThat(doc.principalMinor()).isEqualTo(500_000);
        assertThat(doc.position()).isSameAs(LOAN.position());
        assertThat(doc.displayZone()).isEqualTo(HARARE);
    }

    @Test
    void lifeOfLoanOpensAtZeroAndTheDisbursementRaisesIt() {
        LoanStatementAssembler.Result result = LoanStatementAssembler.assemble(LOAN, null, TO,
                Instant.parse("2026-09-07T10:00:00Z"), HARARE, null, false, List.of(
                        disbursement(500_000, 500_000L),
                        repayment(47_500, 41_667, 5_500, 333, 0, 458_333L)));

        LoanStatementDocument doc = result.document();
        assertThat(doc.from()).isNull();
        assertThat(doc.openingPrincipalMinor()).isZero();
        assertThat(doc.totals().disbursedMinor()).isEqualTo(500_000);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(458_333);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void arithmeticFillsABalancelessLineAndTheCoreWinsWhereItDisagrees() {
        LoanStatementAssembler.Result result = assemble(500_000L, true, List.of(
                // No balance from the core: arithmetic fills it.
                repayment(47_500, 41_667, 5_500, 333, 0, null),
                // The core says 400 000 where arithmetic says 416 666: the
                // core wins, the disagreement is counted.
                repayment(47_500, 41_667, 5_500, 333, 0, 400_000L)));

        LoanStatementDocument doc = result.document();
        assertThat(doc.lines()).extracting(LoanStatementLine::principalBalanceAfterMinor)
                .containsExactly(458_333L, 400_000L);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(400_000);
        assertThat(result.balanceMismatches()).isEqualTo(1);
    }

    @Test
    void reversedRowsAreShownButMoveNothingAndTotalNothing() {
        LoanStatementAssembler.Result result = assemble(500_000L, true, List.of(
                entry(REPAYMENT, 47_500, null, 0, null, true)));

        LoanStatementDocument doc = result.document();
        assertThat(doc.lines()).hasSize(1);
        assertThat(doc.lines().get(0).reversed()).isTrue();
        assertThat(doc.lines().get(0).principalBalanceAfterMinor()).isEqualTo(500_000);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(500_000);
        assertThat(doc.totals().repaidMinor()).isZero();
    }

    @Test
    void writeOffsAndOtherMovementsWalkTheBalanceAndTotalWhereTheyBelong() {
        LoanStatementAssembler.Result result = assemble(375_000L, true, List.of(
                // A refund back to the borrower: raises the balance, totals nothing.
                entry(OTHER, 20_000, 20_000L, 20_000, 395_000L, false),
                entry(WRITE_OFF, 395_000, 395_000L, -395_000, 0L, false)));

        LoanStatementDocument doc = result.document();
        assertThat(doc.lines()).extracting(LoanStatementLine::principalBalanceAfterMinor)
                .containsExactly(395_000L, 0L);
        assertThat(doc.totals().writtenOffMinor()).isEqualTo(395_000);
        assertThat(doc.totals().repaidMinor()).isZero();
        assertThat(doc.totals().disbursedMinor()).isZero();
        assertThat(doc.closingPrincipalMinor()).isZero();
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void aNoneRowThatDisagreesWithTheWalkIsCountedNotHidden() {
        LoanStatementAssembler.Result result = assemble(500_000L, true, List.of(
                entry(NONE, 500_000, null, 0, 490_000L, false)));

        assertThat(result.document().closingPrincipalMinor()).isEqualTo(490_000);
        assertThat(result.balanceMismatches()).isEqualTo(1);
    }

    @Test
    void backDerivesTheOpeningWhenTheAnchorIsSilentButAnInPeriodLineHasABalance() {
        LoanStatementAssembler.Result result = assemble(null, true, List.of(
                entry(REPAYMENT, 47_500, null, 0, null, true),
                repayment(47_500, 41_667, 5_500, 333, 0, 458_333L)));

        assertThat(result.document().openingPrincipalMinor()).isEqualTo(500_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void refusesWhenNothingReachableCarriesAPrincipalBalance() {
        assertThatThrownBy(() -> assemble(null, true, List.of(
                repayment(47_500, 41_667, 5_500, 333, 0, null))))
                .isInstanceOf(StatementUnavailableException.class);
    }

    @Test
    void emptyPeriodClosesWhereItOpened() {
        LoanStatementDocument doc = assemble(375_000L, true, List.of()).document();

        assertThat(doc.openingPrincipalMinor()).isEqualTo(375_000);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(375_000);
        assertThat(doc.lines()).isEmpty();
    }
}
