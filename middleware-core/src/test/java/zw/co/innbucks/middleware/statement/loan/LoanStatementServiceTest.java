package zw.co.innbucks.middleware.statement.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.value.LoanEntryKind;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementProperties;
import zw.co.innbucks.middleware.statement.StatementRequestException;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the ONE-WALK paging: newest first from today, skipping past the
 * period, keeping it, and stopping the moment a pre-period entry anchors the
 * opening principal — plus the period defaults and the ceilings.
 */
class LoanStatementServiceTest {

    private static final OperatorCredential OPERATOR = new OperatorCredential("Basic b3A6cHc=");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T23:30:00Z"), ZoneOffset.UTC);

    private final CoreOperatorPort operatorPort = mock(CoreOperatorPort.class);
    private LoanStatementService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CoreOperatorPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(operatorPort);
        when(operatorPort.authorizeAndDescribeLoan(OPERATOR, "9")).thenReturn(LoanStatementAssemblerTest.LOAN);
        // pageSize 2 so the paging loop is actually exercised; maxEntries 4.
        service = new LoanStatementService(new CountryProperties(Country.ZW),
                new StatementProperties(92, 4, 2, 30), provider, CLOCK, new SimpleMeterRegistry());
    }

    private static LoanTransactionEntry repayment(String id, LocalDate date, Long balanceAfter) {
        return new LoanTransactionEntry(id, null, date, "Repayment", LoanEntryKind.REPAYMENT,
                new MinorUnits(47_500, "USD"), new MinorUnits(41_667, "USD"), new MinorUnits(5_833, "USD"),
                null, null, -41_667, balanceAfter, false);
    }

    private static LoanTransactionEntry reversed(String id, LocalDate date) {
        return new LoanTransactionEntry(id, null, date, "Repayment", LoanEntryKind.REPAYMENT,
                new MinorUnits(47_500, "USD"), null, null, null, null, 0, null, true);
    }

    private static LoanTransactionEntry disbursement(String id, LocalDate date) {
        return new LoanTransactionEntry(id, null, date, "Disbursement", LoanEntryKind.DISBURSEMENT,
                new MinorUnits(500_000, "USD"), null, null, null, null, 500_000, 500_000L, false);
    }

    private void page(int page, LoanTransactionEntry... entries) {
        when(operatorPort.listLoanTransactions(OPERATOR, "9", page, 2))
                .thenReturn(new LoanTransactionPage(List.of(entries), null));
    }

    @Test
    void walksNewestFirstKeepsThePeriodAndStopsAtTheFirstAnchoredPrePeriodEntry() {
        page(0, repayment("904", FROM.plusDays(19), 416_666L), repayment("903", FROM.plusDays(9), 458_333L));
        page(1, repayment("902", FROM.minusDays(7), 500_000L), disbursement("901", LocalDate.of(2026, 3, 1)));

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9", FROM, TO);

        assertThat(doc.openingPrincipalMinor()).isEqualTo(500_000);
        assertThat(doc.lines()).extracting(LoanStatementLine::coreId).containsExactly("903", "904");
        assertThat(doc.closingPrincipalMinor()).isEqualTo(416_666);
        assertThat(doc.from()).isEqualTo(FROM);
        assertThat(doc.to()).isEqualTo(TO);
        // The anchor was on page 1; nothing older is ever fetched.
        verify(operatorPort, never()).listLoanTransactions(eq(OPERATOR), eq("9"), eq(2), anyInt());
    }

    @Test
    void noFromMeansTheLifeOfTheLoanAndNoToMeansTodayInTheCellsZone() {
        // A full first page means "there may be more": the walk asks for the
        // next one and stops on the short (here empty) page.
        page(0, repayment("903", FROM.plusDays(9), 458_333L), disbursement("901", LocalDate.of(2026, 3, 1)));
        page(1);

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9", null, null);

        assertThat(doc.from()).isNull();
        // 23:30Z on the 8th is already the 9th in Harare (UTC+2).
        assertThat(doc.to()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(doc.openingPrincipalMinor()).isZero();
        assertThat(doc.lines()).extracting(LoanStatementLine::coreId).containsExactly("901", "903");
        assertThat(doc.totals().disbursedMinor()).isEqualTo(500_000);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(458_333);
    }

    @Test
    void entriesNewerThanTheRequestedEndAreReadPastNotKept() {
        page(0, repayment("904", FROM.plusDays(19), 416_666L), repayment("903", FROM.plusDays(9), 458_333L));
        page(1, disbursement("901", LocalDate.of(2026, 3, 1)));

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9", FROM, FROM.plusDays(14));

        assertThat(doc.lines()).extracting(LoanStatementLine::coreId).containsExactly("903");
        assertThat(doc.openingPrincipalMinor()).isEqualTo(500_000);
        assertThat(doc.closingPrincipalMinor()).isEqualTo(458_333);
    }

    @Test
    void anchorWalksPastReversedAndBalancelessRowsAddingTheirEffectsBack() {
        // Pre-period, newest first: a reversed row (ignored), a balance-less
        // repayment (its −41 667 is added back), then the anchor at 500 000.
        page(0, reversed("905", FROM.minusDays(1)), repayment("904", FROM.minusDays(2), null));
        page(1, repayment("903", FROM.minusDays(3), 500_000L), disbursement("901", LocalDate.of(2026, 3, 1)));

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9", FROM, TO);

        assertThat(doc.openingPrincipalMinor()).isEqualTo(458_333);
        assertThat(doc.lines()).isEmpty();
        assertThat(doc.closingPrincipalMinor()).isEqualTo(458_333);
    }

    @Test
    void aLoanWithNoHistoryBeforeThePeriodOpensAtZero() {
        page(0, disbursement("901", FROM.plusDays(1)));

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9", FROM, TO);

        assertThat(doc.openingPrincipalMinor()).isZero();
        assertThat(doc.closingPrincipalMinor()).isEqualTo(500_000);
    }

    @Test
    void tooManyEntriesInThePeriodRefusesInsteadOfRenderingUnbounded() {
        page(0, repayment("a", FROM.plusDays(9), 1L), repayment("b", FROM.plusDays(9), 1L));
        page(1, repayment("c", FROM.plusDays(9), 1L), repayment("d", FROM.plusDays(9), 1L));
        page(2, repayment("e", FROM.plusDays(9), 1L), repayment("f", FROM.plusDays(9), 1L));

        assertThatThrownBy(() -> service.statementForOperator(OPERATOR, "9", FROM, TO))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> {
                    assertThat(((StatementRequestException) ex).errorCode()).isEqualTo("statement_too_large");
                    assertThat(((StatementRequestException) ex).unprocessable()).isTrue();
                });
    }

    @Test
    void anUnboundedReadPastRecentActivityIsRefusedTooNotPagedForever() {
        // Every page is newer than the period asked for; the scan ceiling
        // (2 × maxEntries = 8 rows) trips before a fifth page is fetched.
        LocalDate recent = TO.plusDays(30);
        for (int p = 0; p < 6; p++) {
            page(p, repayment("n" + p + "a", recent, 1L), repayment("n" + p + "b", recent, 1L));
        }

        assertThatThrownBy(() -> service.statementForOperator(OPERATOR, "9", FROM, TO))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> assertThat(((StatementRequestException) ex).errorCode())
                        .isEqualTo("statement_too_large"));
        verify(operatorPort, never()).listLoanTransactions(eq(OPERATOR), eq("9"), eq(5), anyInt());
    }

    @Test
    void invertedPeriodIsRefusedBeforeAnyCoreRead() {
        assertThatThrownBy(() -> service.statementForOperator(OPERATOR, "9", TO, FROM))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> assertThat(((StatementRequestException) ex).errorCode())
                        .isEqualTo("statement_period_invalid"));
        verify(operatorPort, never()).authorizeAndDescribeLoan(eq(OPERATOR), eq("9"));
    }

    @Test
    void periodLongerThanTheDepositCeilingIsFineForALoan() {
        page(0, disbursement("901", LocalDate.of(2026, 3, 1)));

        LoanStatementDocument doc = service.statementForOperator(OPERATOR, "9",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(doc.lines()).extracting(LoanStatementLine::coreId).containsExactly("901");
    }

    @Test
    void unavailableWhenTheCellHasNoOperatorGateway() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CoreOperatorPort> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        LoanStatementService withoutGateway = new LoanStatementService(new CountryProperties(Country.ZW),
                new StatementProperties(92, 4, 2, 30), absent, CLOCK, new SimpleMeterRegistry());

        assertThatThrownBy(() -> withoutGateway.statementForOperator(OPERATOR, "9", FROM, TO))
                .isInstanceOf(StatementUnavailableException.class);
    }
}
