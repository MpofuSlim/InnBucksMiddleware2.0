package zw.co.innbucks.middleware.statement;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.exception.CoreBankingException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerName;
import zw.co.innbucks.middleware.customer.CustomerNameResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.transactions.AccountOwnershipException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds the fixed-period account statement — the document a customer keeps,
 * as opposed to the paged feed on {@code /me/accounts/{id}/transactions}.
 *
 * <p>Sourced from the CORE for the same reason the feed is: interest, fees
 * and teller corrections never crossed this middleware, and a statement that
 * omitted them would not reconcile against the balance on {@code /me/accounts}.
 * A movement parked as UNKNOWN has no confirmed core entry and appears only
 * once it reconciles — on a document of record, absent beats invented.
 *
 * <p>The one statement-specific core read is the OPENING-BALANCE PROBE: a
 * small newest-first query ending the day before the period, so the opening
 * balance is anchored on the core's own running balance rather than derived
 * by summation (see {@link StatementAssembler} for the full balance policy).
 * The probe leans on the adapter's documented ordering — newest first,
 * deterministic tiebreak — which the Veengu adapter will owe as well.
 */
@Service
@Slf4j
@EnableConfigurationProperties(StatementProperties.class)
public class StatementService {

    /**
     * Pre-period entries examined for a usable opening anchor. More than one
     * because the latest entry can be reversed or balance-less; the walk adds
     * back the amounts of the entries it skips past, so anchoring deeper never
     * changes the answer, only rescues it.
     */
    private static final int ANCHOR_PROBE_ENTRIES = 5;

    private final CustomerRepository customerRepository;
    private final CoreBankingPort corePort;
    private final CustomerNameResolver nameResolver;
    private final CountryProperties countryProperties;
    private final StatementProperties properties;
    private final ObjectProvider<CoreOperatorPort> operatorPort;
    private final Counter generatedCustomer;
    private final Counter generatedConsole;
    private final Counter balanceMismatches;

    public StatementService(CustomerRepository customerRepository, CoreBankingPort corePort,
                            CustomerNameResolver nameResolver, CountryProperties countryProperties,
                            StatementProperties properties, ObjectProvider<CoreOperatorPort> operatorPort,
                            MeterRegistry meterRegistry) {
        this.customerRepository = customerRepository;
        this.corePort = corePort;
        this.nameResolver = nameResolver;
        this.countryProperties = countryProperties;
        this.properties = properties;
        this.operatorPort = operatorPort;
        this.generatedCustomer = meterRegistry.counter("innbucks.statement.generated", "surface", "customer");
        this.generatedConsole = meterRegistry.counter("innbucks.statement.generated", "surface", "console");
        this.balanceMismatches = meterRegistry.counter("innbucks.statement.balance_mismatch");
    }

    public StatementDocument statementFor(UUID customerId, String accountId, LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        Customer customer = requireMappedCustomer(customerId);
        DepositAccountRef account = requireOwnedAccount(customer, accountId);
        StatementDocument document = assembleFor(account.account(), accountId,
                account.currencyCode(), displayName(customer), customer.getMsisdn(), from, to);
        generatedCustomer.increment();
        return document;
    }

    /**
     * The console (bank-issued) statement. Same balance policy, same
     * renderings — the ONLY differences are who vouches for the caller and
     * whose name heads the document. Authentication and authorisation are
     * fully delegated to the core via {@link CoreOperatorPort}: this method
     * runs no ownership check of its own because the core already refused
     * operators who may not read the account.
     */
    public StatementDocument statementForOperator(OperatorCredential credential,
                                                  String savingsAccountId,
                                                  LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        CoreOperatorPort port = operatorPort.getIfAvailable();
        if (port == null) {
            throw new StatementUnavailableException(
                    "This cell's core adapter does not support operator-credential reads.");
        }
        OperatorAccountView account = port.authorizeAndDescribeAccount(credential, savingsAccountId);
        if (account.accountExternalId() == null) {
            // Transactions are read through the port by external reference; a
            // branch-created account has none. Refusing beats guessing.
            throw StatementRequestException.unsupportedAccount();
        }
        StatementDocument document = assembleFor(new AccountRef(account.accountExternalId()),
                account.accountNumber(), account.currencyCode(), account.holderName(),
                account.holderMobile(), from, to);
        generatedConsole.increment();
        return document;
    }

    private StatementDocument assembleFor(AccountRef account, String displayAccountId,
                                          String currencyCode, String holderName, String msisdn,
                                          LocalDate from, LocalDate to) {
        Anchor anchor = openingAnchor(account, from);
        List<TransactionEntry> chronological = collectPeriod(account, from, to);

        StatementAssembler.Result result = StatementAssembler.assemble(
                displayAccountId, currencyCode, holderName, msisdn,
                from, to, Instant.now(), countryProperties.country().zoneId(),
                anchor.openingMinor(), anchor.historyBeforePeriod(), chronological);

        if (result.balanceMismatches() > 0) {
            // The core's own running balances disagreed with its amounts. The
            // document trusts the core's balances (it is the book of record);
            // this is the operator signal that the two are drifting apart.
            balanceMismatches.increment(result.balanceMismatches());
            log.warn("Statement for account {} had {} core balance/amount disagreement(s) in {}..{}",
                    displayAccountId, result.balanceMismatches(), from, to);
        }
        return result.document();
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw StatementRequestException.invalidPeriod(
                    "'to' (" + to + ") is before 'from' (" + from + ").");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > properties.maxPeriodDays()) {
            throw StatementRequestException.periodTooLong(properties.maxPeriodDays());
        }
    }

    /**
     * The latest pre-period entries, newest first. The anchor is the first
     * entry carrying a running balance, PLUS the signed amounts of any newer
     * entries walked past to reach it — balance-after semantics make that
     * exact, not approximate. Reversed entries are skipped as anchors and
     * contribute nothing, matching the assembler's policy.
     */
    private Anchor openingAnchor(AccountRef account, LocalDate from) {
        TransactionPage before = corePort.listTransactions(new TransactionHistoryQuery(
                account, null, from.minusDays(1), 0, ANCHOR_PROBE_ENTRIES));
        if (before.entries().isEmpty()) {
            return new Anchor(null, false);
        }
        long newerDelta = 0;
        for (TransactionEntry entry : before.entries()) {
            if (entry.reversed()) {
                continue;
            }
            if (entry.runningBalance() != null) {
                return new Anchor(entry.runningBalance().amount() + newerDelta, true);
            }
            newerDelta += signed(entry);
        }
        // History exists but nothing probed carries a balance; the assembler
        // will back-derive from the period itself, or refuse honestly.
        return new Anchor(null, true);
    }

    private List<TransactionEntry> collectPeriod(AccountRef account, LocalDate from, LocalDate to) {
        List<TransactionEntry> newestFirst = new ArrayList<>();
        int offset = 0;
        int pageSize = properties.pageSize();
        while (true) {
            TransactionPage page = corePort.listTransactions(
                    new TransactionHistoryQuery(account, from, to, offset, pageSize));
            newestFirst.addAll(page.entries());
            if (newestFirst.size() > properties.maxEntries()) {
                throw StatementRequestException.tooLarge(properties.maxEntries());
            }
            if (page.entries().size() < pageSize) {
                break;
            }
            offset += page.entries().size();
        }
        List<TransactionEntry> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    /**
     * Best-effort: the statement identifies its holder by MSISDN either way,
     * and the transactions read just succeeded — refusing the whole document
     * because the separate profile read blipped would fail the customer over
     * a nicety. Core failures are the ONLY thing swallowed here.
     */
    private String displayName(Customer customer) {
        try {
            CustomerName name = nameResolver.resolve(customer.getCoreExternalId());
            if (name == null) {
                return null;
            }
            String display = ((name.firstName() == null ? "" : name.firstName()) + " "
                    + (name.lastName() == null ? "" : name.lastName())).trim();
            return display.isEmpty() ? null : display;
        } catch (CoreBankingException e) {
            log.warn("Statement renders without a customer name: profile read failed: {}", e.getMessage());
            return null;
        }
    }

    private DepositAccountRef requireOwnedAccount(Customer customer, String accountId) {
        return corePort.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId()))
                .stream()
                .filter(a -> a.account().externalId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new AccountOwnershipException(accountId));
    }

    private Customer requireMappedCustomer(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ProfileNotFoundException(customerId));
        if (customer.getCoreExternalId() == null) {
            throw new ProfileNotFoundException(customerId);
        }
        return customer;
    }

    private static long signed(TransactionEntry entry) {
        long amount = entry.amount().amount();
        return entry.direction() == TransactionDirection.CREDIT ? amount : -amount;
    }

    private record Anchor(Long openingMinor, boolean historyBeforePeriod) {
    }
}
