package zw.co.innbucks.middleware.statement.loan;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;
import zw.co.innbucks.middleware.statement.StatementProperties;
import zw.co.innbucks.middleware.statement.StatementRequestException;
import zw.co.innbucks.middleware.statement.StatementUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the bank-issued LOAN statement for the back-office console. Console
 * only, by design: the mobile app has no loan surface, and — unlike the
 * deposit statement — every read here rides the OPERATOR'S credential
 * ({@link CoreOperatorPort}), so the core enforces its loan-read permission
 * on each call and this middleware's own service account never needs loan
 * access at all.
 *
 * <p><b>The period is optional, and there is no period ceiling.</b> The
 * request that matters to a credit manager is "the statement of this loan" —
 * everything since disbursement — so an absent {@code from} means the life of
 * the loan and an absent {@code to} means today. The deposit statement's
 * 92-day ceiling exists because a transactional account can post hundreds of
 * entries a week; a loan posts a handful a month (accruals are excluded at
 * the wire), so the ENTRY ceiling ({@code innbucks.statement.max-entries}) is
 * the protection the request thread needs, and it still applies.
 *
 * <p><b>One walk, newest first.</b> The core's loan history has no date
 * filter, so the service pages from today backwards: entries after {@code to}
 * are skipped, entries in the period are kept, and the first entries before
 * {@code from} are the opening-principal PROBE — the latest one carrying a
 * balance anchors the opening, with the effects of any balance-less rows
 * walked past added back (the same anchoring the deposit statement does in
 * a separate query). Paging stops as soon as the anchor is found.
 */
@Service
@Slf4j
public class LoanStatementService {

    /** Pre-period entries examined for an anchor before giving up on one. */
    private static final int ANCHOR_PROBE_ENTRIES = 5;

    private final CountryProperties countryProperties;
    private final StatementProperties properties;
    private final ObjectProvider<CoreOperatorPort> operatorPort;
    private final Clock clock;
    private final Counter generated;
    private final Counter balanceMismatches;

    public LoanStatementService(CountryProperties countryProperties, StatementProperties properties,
                                ObjectProvider<CoreOperatorPort> operatorPort, Clock clock,
                                MeterRegistry meterRegistry) {
        this.countryProperties = countryProperties;
        this.properties = properties;
        this.operatorPort = operatorPort;
        this.clock = clock;
        this.generated = meterRegistry.counter("innbucks.loan_statement.generated", "surface", "console");
        this.balanceMismatches = meterRegistry.counter("innbucks.loan_statement.balance_mismatch");
    }

    /**
     * @param from inclusive period start, or null for the life of the loan
     * @param to   inclusive period end, or null for today (in the cell's civil zone)
     */
    public LoanStatementDocument statementForOperator(OperatorCredential credential, String loanId,
                                                      LocalDate from, LocalDate to) {
        ZoneId zone = countryProperties.country().zoneId();
        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock.withZone(zone));
        if (from != null && effectiveTo.isBefore(from)) {
            throw StatementRequestException.invalidPeriod(
                    "'to' (" + effectiveTo + ") is before 'from' (" + from + ").");
        }
        CoreOperatorPort port = operatorPort.getIfAvailable();
        if (port == null) {
            throw new StatementUnavailableException(
                    "This cell's core adapter does not support operator-credential reads.");
        }
        OperatorLoanView loan = port.authorizeAndDescribeLoan(credential, loanId);
        Walk walk = walk(port, credential, loanId, from, effectiveTo);

        LoanStatementAssembler.Result result = LoanStatementAssembler.assemble(
                loan, from, effectiveTo, Instant.now(clock), zone,
                walk.anchor, walk.historyBeforePeriod, walk.chronological());

        if (result.balanceMismatches() > 0) {
            // The core's own principal balances disagreed with its portions.
            // The document trusts the core's balances (it is the book of
            // record); this is the operator signal that the two are drifting.
            balanceMismatches.increment(result.balanceMismatches());
            log.warn("Loan statement for {} had {} core balance/portion disagreement(s) in {}..{}",
                    loanId, result.balanceMismatches(), from == null ? "inception" : from, effectiveTo);
        }
        generated.increment();
        return result.document();
    }

    private static final class Walk {
        final List<LoanTransactionEntry> newestFirst = new ArrayList<>();
        Long anchor;
        boolean historyBeforePeriod;
        long newerDelta;
        int probed;

        List<LoanTransactionEntry> chronological() {
            List<LoanTransactionEntry> chronological = new ArrayList<>(newestFirst);
            Collections.reverse(chronological);
            return chronological;
        }
    }

    private Walk walk(CoreOperatorPort port, OperatorCredential credential, String loanId,
                      LocalDate from, LocalDate to) {
        Walk walk = new Walk();
        int pageSize = properties.pageSize();
        int maxEntries = properties.maxEntries();
        // Rows newer than `to` are read past, never kept; this bounds that
        // read for a loan whose recent activity dwarfs the period asked for.
        // The remedy is honest — the walk starts from today, so a more RECENT
        // period is what gets it under the cap.
        int scanCeiling = maxEntries * 2;
        int scanned = 0;
        int page = 0;
        while (true) {
            LoanTransactionPage p = port.listLoanTransactions(credential, loanId, page, pageSize);
            for (LoanTransactionEntry entry : p.entries()) {
                scanned++;
                if (entry.valueDate().isAfter(to)) {
                    continue;
                }
                if (from == null || !entry.valueDate().isBefore(from)) {
                    walk.newestFirst.add(entry);
                    if (walk.newestFirst.size() > maxEntries) {
                        throw StatementRequestException.loanTooLarge(maxEntries);
                    }
                    continue;
                }
                // Before the period: the anchor probe. Reversed rows carry no
                // trustworthy balance and moved nothing; balance-less rows
                // are walked past with their effect added back.
                walk.historyBeforePeriod = true;
                if (entry.reversed()) {
                    continue;
                }
                if (entry.principalBalanceAfter() != null) {
                    walk.anchor = entry.principalBalanceAfter() + walk.newerDelta;
                    return walk;
                }
                walk.newerDelta += entry.principalDeltaMinor();
                if (++walk.probed >= ANCHOR_PROBE_ENTRIES) {
                    // History exists but nothing probed carries a balance; the
                    // assembler back-derives from the period, or refuses.
                    return walk;
                }
            }
            if (p.entries().size() < pageSize) {
                return walk;
            }
            if (scanned >= scanCeiling) {
                throw StatementRequestException.loanTooLarge(maxEntries);
            }
            page++;
        }
    }
}
