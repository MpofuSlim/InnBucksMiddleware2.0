package zw.co.innbucks.middleware.statement;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.CREDIT;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.DEBIT;

/**
 * Pins the balance policy: the core's running balance anchors and wins;
 * arithmetic fills its silences; a statement with no honest anchor is refused,
 * never invented. Every case here is a document a customer could hand to a
 * bank, so the numbers are asserted exactly.
 */
class StatementAssemblerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);
    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

    private static int nextId = 1;

    private static TransactionEntry entry(TransactionDirection direction, long amountMinor,
                                          Long runningBalanceMinor, boolean reversed) {
        return new TransactionEntry(String.valueOf(nextId++), null, direction, "Entry",
                new MinorUnits(amountMinor, "USD"),
                runningBalanceMinor == null ? null : new MinorUnits(runningBalanceMinor, "USD"),
                FROM.plusDays(2), reversed);
    }

    private static TransactionEntry neutral(long amountMinor, Long runningBalanceMinor) {
        return new TransactionEntry(String.valueOf(nextId++), null, DEBIT, "Waive Charge",
                new MinorUnits(amountMinor, "USD"),
                runningBalanceMinor == null ? null : new MinorUnits(runningBalanceMinor, "USD"),
                FROM.plusDays(2), false, true);
    }

    private static StatementAssembler.Result assemble(Long anchor, boolean historyBefore,
                                                      List<TransactionEntry> chronological) {
        return StatementAssembler.assemble("acct:wallet", DepositAccountKind.SAVINGS, "USD",
                "Tariro Moyo", "+263771234567",
                FROM, TO, Instant.parse("2026-09-07T10:00:00Z"), HARARE,
                anchor, historyBefore, chronological);
    }

    @Test
    void anchoredOpeningWithArithmeticFillForBalancelessEntries() {
        StatementAssembler.Result result = assemble(10_000L, true, List.of(
                entry(CREDIT, 5_000, null, false),
                entry(DEBIT, 1_000, 14_000L, false)));

        StatementDocument doc = result.document();
        assertThat(doc.openingBalanceMinor()).isEqualTo(10_000);
        assertThat(doc.lines()).extracting(StatementLine::balanceAfterMinor)
                .containsExactly(15_000L, 14_000L);
        assertThat(doc.closingBalanceMinor()).isEqualTo(14_000);
        assertThat(doc.totalCreditsMinor()).isEqualTo(5_000);
        assertThat(doc.totalDebitsMinor()).isEqualTo(1_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    /**
     * The console dev's reproduction, in miniature: a waived charge in range
     * carried an amount but moved no money. Before the fix it landed in
     * totalDebits, and opening + credits − debits disagreed with the closing
     * balance by exactly the waived amount — the document disagreeing with
     * itself. The line must stay ON the statement, flagged, at the unchanged
     * balance.
     */
    @Test
    void waivedChargeIsShownButMovesNeitherTotalsNorBalance() {
        StatementAssembler.Result result = assemble(4_049L, true, List.of(
                entry(CREDIT, 16_000, 20_049L, false),
                neutral(1_000, 20_049L),
                entry(DEBIT, 7_500, 12_549L, false)));

        StatementDocument doc = result.document();
        assertThat(doc.totalCreditsMinor()).isEqualTo(16_000);
        assertThat(doc.totalDebitsMinor()).isEqualTo(7_500);
        assertThat(doc.closingBalanceMinor()).isEqualTo(12_549);
        assertThat(doc.openingBalanceMinor() + doc.totalCreditsMinor() - doc.totalDebitsMinor())
                .isEqualTo(doc.closingBalanceMinor());
        assertThat(doc.lines()).extracting(StatementLine::balanceAfterMinor)
                .containsExactly(20_049L, 20_049L, 12_549L);
        assertThat(doc.lines().get(1).balanceNeutral()).isTrue();
        assertThat(doc.lines().get(1).amountMinor()).isEqualTo(1_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void balancelessNeutralEntryDoesNotCorruptTheBackDerivedOpening() {
        // Back-derive walks the in-period entries; the balance-less waive in
        // the middle must contribute ZERO or the opening lands off by its
        // amount: 11 000 - 3 000 + 2 000 = 10 000.
        StatementAssembler.Result result = assemble(null, true, List.of(
                entry(DEBIT, 2_000, null, false),
                neutral(1_000, null),
                entry(CREDIT, 3_000, 11_000L, false)));

        assertThat(result.document().openingBalanceMinor()).isEqualTo(10_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void aNeutralEntryWhoseCoreBalanceDisagreesStillSurfacesTheMismatch() {
        // Its balance should be the UNCHANGED balance; a core that reports
        // something else wins (book of record) and is counted, not hidden.
        StatementAssembler.Result result = assemble(10_000L, true, List.of(
                neutral(1_000, 9_000L)));

        assertThat(result.document().closingBalanceMinor()).isEqualTo(9_000);
        assertThat(result.balanceMismatches()).isEqualTo(1);
    }

    @Test
    void accountWithNoHistoryBeforePeriodOpensAtZero() {
        StatementAssembler.Result result = assemble(null, false, List.of(
                entry(CREDIT, 5_000, 5_000L, false)));

        assertThat(result.document().openingBalanceMinor()).isZero();
        assertThat(result.document().closingBalanceMinor()).isEqualTo(5_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void backDerivesOpeningFromFirstInPeriodBalanceWhenAnchorIsSilent() {
        // Pre-period history exists but reported no balance. First entry has
        // none either; the second does — opening must land at 10 000 so the
        // arithmetic joins up: 10 000 - 2 000 + 3 000 = 11 000.
        StatementAssembler.Result result = assemble(null, true, List.of(
                entry(DEBIT, 2_000, null, false),
                entry(CREDIT, 3_000, 11_000L, false)));

        StatementDocument doc = result.document();
        assertThat(doc.openingBalanceMinor()).isEqualTo(10_000);
        assertThat(doc.lines()).extracting(StatementLine::balanceAfterMinor)
                .containsExactly(8_000L, 11_000L);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void refusesWhenNoBalanceIsReachableAnywhere() {
        assertThatThrownBy(() -> assemble(null, true, List.of(
                entry(CREDIT, 5_000, null, false),
                entry(DEBIT, 1_000, null, false))))
                .isInstanceOf(StatementUnavailableException.class);
    }

    @Test
    void coreBalanceWinsOverArithmeticAndTheDisagreementIsCounted() {
        // Arithmetic says 11 000; the core says 12 000. The core is the book
        // of record, so the document shows 12 000 — and the caller hears
        // about it instead of the drift being absorbed silently.
        StatementAssembler.Result result = assemble(10_000L, true, List.of(
                entry(CREDIT, 1_000, 12_000L, false)));

        assertThat(result.document().lines().get(0).balanceAfterMinor()).isEqualTo(12_000);
        assertThat(result.document().closingBalanceMinor()).isEqualTo(12_000);
        assertThat(result.balanceMismatches()).isEqualTo(1);
    }

    @Test
    void reversedEntriesAreShownButNeutral() {
        StatementAssembler.Result result = assemble(10_000L, true, List.of(
                entry(CREDIT, 5_000, null, true),
                entry(CREDIT, 2_000, 12_000L, false)));

        StatementDocument doc = result.document();
        assertThat(doc.lines()).hasSize(2);
        assertThat(doc.lines().get(0).reversed()).isTrue();
        assertThat(doc.lines().get(0).balanceAfterMinor()).isEqualTo(10_000);
        assertThat(doc.totalCreditsMinor()).isEqualTo(2_000);
        assertThat(doc.closingBalanceMinor()).isEqualTo(12_000);
        assertThat(result.balanceMismatches()).isZero();
    }

    @Test
    void backDerivationSkipsReversedEntries() {
        StatementAssembler.Result result = assemble(null, true, List.of(
                entry(DEBIT, 9_999, null, true),
                entry(CREDIT, 3_000, 13_000L, false)));

        assertThat(result.document().openingBalanceMinor()).isEqualTo(10_000);
    }

    @Test
    void emptyPeriodRendersAnchorAsBothBalances() {
        StatementAssembler.Result result = assemble(7_000L, true, List.of());

        StatementDocument doc = result.document();
        assertThat(doc.lines()).isEmpty();
        assertThat(doc.openingBalanceMinor()).isEqualTo(7_000);
        assertThat(doc.closingBalanceMinor()).isEqualTo(7_000);
        assertThat(doc.totalCreditsMinor()).isZero();
        assertThat(doc.totalDebitsMinor()).isZero();
    }
}
