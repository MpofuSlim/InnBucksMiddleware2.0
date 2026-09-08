package zw.co.innbucks.middleware.statement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountKind;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.TransactionDirection;
import zw.co.innbucks.middleware.corebanking.value.TransactionEntry;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerNameResolver;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.ProfileCacheProperties;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.transactions.AccountOwnershipException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.CREDIT;
import static zw.co.innbucks.middleware.corebanking.value.TransactionDirection.DEBIT;

class StatementServiceTest {

    private static final UUID CUSTOMER = UUID.fromString("3f0d1c2e-8a4b-4b6e-9f1d-2c3b4a5d6e7f");
    private static final String WALLET = CUSTOMER + ":wallet";
    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final CoreOperatorPort operatorPort = mock(CoreOperatorPort.class);

    private StatementService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER);
        customer.setCountry("ZW");
        customer.setMsisdn("+263771234567");
        customer.setCoreExternalId(CUSTOMER.toString());
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(port.listDepositAccountRefs(new CoreCustomerRef(CUSTOMER.toString())))
                .thenReturn(List.of(new DepositAccountRef(
                        new AccountRef(WALLET), "Wallet", "USD", "000000010")));
        when(port.getProfile(new CoreCustomerRef(CUSTOMER.toString())))
                .thenReturn(new CustomerProfile(new CoreCustomerRef(CUSTOMER.toString()),
                        "Tariro", "Moyo", "ACTIVE"));

        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        CustomerNameResolver resolver = new CustomerNameResolver(provider,
                new ProfileCacheProperties(false, Duration.ofMinutes(5), 1000), new SimpleMeterRegistry());

        ObjectProvider<CoreOperatorPort> operatorProvider = mock(ObjectProvider.class);
        when(operatorProvider.getIfAvailable()).thenReturn(operatorPort);

        // pageSize 2 so the paging loop is actually exercised.
        service = new StatementService(customers, port, resolver,
                new CountryProperties(Country.ZW), new StatementProperties(92, 4, 2, 30),
                operatorProvider, new SimpleMeterRegistry());
    }

    private static TransactionEntry entry(String id, TransactionDirection direction, long amount,
                                          Long runningBalance, LocalDate date) {
        return new TransactionEntry(id, null, direction, "Entry",
                new MinorUnits(amount, "USD"),
                runningBalance == null ? null : new MinorUnits(runningBalance, "USD"),
                date, false);
    }

    private TransactionHistoryQuery anchorQuery() {
        return new TransactionHistoryQuery(new AccountRef(WALLET), null, FROM.minusDays(1), 0, 5);
    }

    private TransactionHistoryQuery periodQuery(int offset) {
        return new TransactionHistoryQuery(new AccountRef(WALLET), FROM, TO, offset, 2);
    }

    @Test
    void assemblesAcrossPagesInChronologicalOrderWithAnchoredOpening() {
        when(port.listTransactions(anchorQuery())).thenReturn(new TransactionPage(List.of(
                entry("9", CREDIT, 2_000, 10_000L, FROM.minusDays(3))), null));
        // Newest first on the wire, split across two pages by pageSize 2.
        when(port.listTransactions(periodQuery(0))).thenReturn(new TransactionPage(List.of(
                entry("3", DEBIT, 1_000, 14_000L, FROM.plusDays(9)),
                entry("2", CREDIT, 3_000, 15_000L, FROM.plusDays(5))), null));
        when(port.listTransactions(periodQuery(2))).thenReturn(new TransactionPage(List.of(
                entry("1", CREDIT, 2_000, 12_000L, FROM.plusDays(1))), null));

        StatementDocument doc = service.statementFor(CUSTOMER, WALLET, FROM, TO);

        assertThat(doc.openingBalanceMinor()).isEqualTo(10_000);
        assertThat(doc.lines()).extracting(StatementLine::coreId).containsExactly("1", "2", "3");
        assertThat(doc.closingBalanceMinor()).isEqualTo(14_000);
        assertThat(doc.customerName()).isEqualTo("Tariro Moyo");
        assertThat(doc.msisdn()).isEqualTo("+263771234567");
        assertThat(doc.currencyCode()).isEqualTo("USD");
        assertThat(doc.displayZone()).isEqualTo(Country.ZW.zoneId());
    }

    @Test
    void anchorWalksPastBalancelessEntriesAddingTheirAmountsBack() {
        // The latest pre-period entry has no balance; the one before it does.
        // Opening = 10 000 (after the older entry) + 500 (the newer credit).
        when(port.listTransactions(anchorQuery())).thenReturn(new TransactionPage(List.of(
                entry("8", CREDIT, 500, null, FROM.minusDays(1)),
                entry("7", DEBIT, 200, 10_000L, FROM.minusDays(4))), null));
        when(port.listTransactions(periodQuery(0))).thenReturn(new TransactionPage(List.of(), 0L));

        StatementDocument doc = service.statementFor(CUSTOMER, WALLET, FROM, TO);

        assertThat(doc.openingBalanceMinor()).isEqualTo(10_500);
        assertThat(doc.closingBalanceMinor()).isEqualTo(10_500);
    }

    @Test
    void anchorWalkContributesZeroForABalancelessBalanceNeutralEntry() {
        // Newest pre-period entry is a balance-less waived charge: it moved no
        // money, so the anchor must land at 10 000, not 10 000 − 300.
        TransactionEntry waive = new TransactionEntry("9", null, DEBIT, "Waive Charge",
                new MinorUnits(300, "USD"), null, FROM.minusDays(1), false, true);
        when(port.listTransactions(anchorQuery())).thenReturn(new TransactionPage(List.of(
                waive,
                entry("7", DEBIT, 200, 10_000L, FROM.minusDays(4))), null));
        when(port.listTransactions(periodQuery(0))).thenReturn(new TransactionPage(List.of(), 0L));

        StatementDocument doc = service.statementFor(CUSTOMER, WALLET, FROM, TO);

        assertThat(doc.openingBalanceMinor()).isEqualTo(10_000);
    }

    @Test
    void someoneElsesAccountIsRefusedBeforeAnyStatementRead() {
        assertThatThrownBy(() -> service.statementFor(CUSTOMER, "not-mine:wallet", FROM, TO))
                .isInstanceOf(AccountOwnershipException.class);
    }

    @Test
    void unregisteredCustomerIs404Shaped() {
        assertThatThrownBy(() -> service.statementFor(UUID.randomUUID(), WALLET, FROM, TO))
                .isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void periodLongerThanTheCeilingIsRefused() {
        assertThatThrownBy(() -> service.statementFor(CUSTOMER, WALLET, FROM, FROM.plusDays(92)))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> assertThat(((StatementRequestException) ex).errorCode())
                        .isEqualTo("statement_period_too_long"));
    }

    @Test
    void invertedPeriodIsRefused() {
        assertThatThrownBy(() -> service.statementFor(CUSTOMER, WALLET, TO, FROM))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> assertThat(((StatementRequestException) ex).errorCode())
                        .isEqualTo("statement_period_invalid"));
    }

    @Test
    void tooManyEntriesRefusesInsteadOfRenderingUnbounded() {
        when(port.listTransactions(anchorQuery())).thenReturn(new TransactionPage(List.of(), 0L));
        // Every page comes back full — maxEntries 4 trips on the fifth entry.
        when(port.listTransactions(periodQuery(0))).thenReturn(fullPage("a", "b"));
        when(port.listTransactions(periodQuery(2))).thenReturn(fullPage("c", "d"));
        when(port.listTransactions(periodQuery(4))).thenReturn(fullPage("e", "f"));

        assertThatThrownBy(() -> service.statementFor(CUSTOMER, WALLET, FROM, TO))
                .isInstanceOf(StatementRequestException.class)
                .satisfies(ex -> {
                    assertThat(((StatementRequestException) ex).errorCode())
                            .isEqualTo("statement_too_large");
                    assertThat(((StatementRequestException) ex).unprocessable()).isTrue();
                });
    }

    @Test
    void profileReadFailureCostsTheNameNotTheStatement() {
        when(port.getProfile(new CoreCustomerRef(CUSTOMER.toString())))
                .thenThrow(new CoreTransientException(CoreProvider.FINERACT, "core down", null));
        when(port.listTransactions(anchorQuery())).thenReturn(new TransactionPage(List.of(), 0L));
        when(port.listTransactions(periodQuery(0))).thenReturn(new TransactionPage(List.of(), 0L));

        StatementDocument doc = service.statementFor(CUSTOMER, WALLET, FROM, TO);

        assertThat(doc.customerName()).isNull();
        assertThat(doc.msisdn()).isEqualTo("+263771234567");
    }

    @Test
    void operatorStatementDelegatesAuthorisationToTheCoreAndRendersTheHolder() {
        OperatorCredential credential = new OperatorCredential("Basic b3A6cHc=");
        when(operatorPort.authorizeAndDescribeAccount(credential, "17"))
                .thenReturn(new OperatorAccountView(WALLET, DepositAccountKind.SAVINGS, "USD",
                        "000000017", "Shumba Traders", "0771234567"));
        when(port.listTransactions(coreIdAnchorQuery("17"))).thenReturn(new TransactionPage(List.of(
                entry("9", CREDIT, 2_000, 10_000L, FROM.minusDays(3))), null));
        when(port.listTransactions(coreIdPeriodQuery("17", 0))).thenReturn(new TransactionPage(List.of(), 0L));

        StatementDocument doc = service.statementForOperator(credential, "17", FROM, TO);

        // The document is headed by what the CORE said, not by any local row —
        // the account NUMBER identifies it, the holder may have an
        // unnormalised mobile, and no customer table was consulted.
        assertThat(doc.accountId()).isEqualTo("000000017");
        assertThat(doc.customerName()).isEqualTo("Shumba Traders");
        assertThat(doc.msisdn()).isEqualTo("0771234567");
        assertThat(doc.openingBalanceMinor()).isEqualTo(10_000);
        assertThat(doc.accountKind()).isEqualTo(DepositAccountKind.SAVINGS);
    }

    /**
     * The majority console case in practice: a branch-created account with no
     * external reference. The reads are keyed by the CORE'S OWN account id —
     * the exact id the operator was just authorised against — so the account
     * needs no external id at all, and the Mockito stubs above double as the
     * proof (an external-ref query would return null and NPE the assembler).
     */
    @Test
    void operatorStatementCoversBranchAccountsWithoutAnExternalReference() {
        OperatorCredential credential = new OperatorCredential("Basic b3A6cHc=");
        when(operatorPort.authorizeAndDescribeAccount(credential, "23"))
                .thenReturn(new OperatorAccountView(null, DepositAccountKind.FIXED_DEPOSIT, "USD",
                        "000000023", "Walk In", null));
        when(port.listTransactions(coreIdAnchorQuery("23")))
                .thenReturn(new TransactionPage(List.of(), 0L));
        when(port.listTransactions(coreIdPeriodQuery("23", 0)))
                .thenReturn(new TransactionPage(List.of(
                        entry("41", CREDIT, 5_000, 5_000L, FROM.plusDays(2))), 1L));

        StatementDocument doc = service.statementForOperator(credential, "23", FROM, TO);

        assertThat(doc.accountId()).isEqualTo("000000023");
        assertThat(doc.customerName()).isEqualTo("Walk In");
        assertThat(doc.openingBalanceMinor()).isZero();
        assertThat(doc.closingBalanceMinor()).isEqualTo(5_000);
        assertThat(doc.lines()).extracting(StatementLine::coreId).containsExactly("41");
        // The kind the core reported heads the document; nothing else changes.
        assertThat(doc.accountKind()).isEqualTo(DepositAccountKind.FIXED_DEPOSIT);
        assertThat(doc.title()).isEqualTo("Fixed Deposit Statement");
    }

    private static TransactionHistoryQuery coreIdAnchorQuery(String coreAccountId) {
        return TransactionHistoryQuery.byCoreAccountId(coreAccountId, null, FROM.minusDays(1), 0, 5);
    }

    private static TransactionHistoryQuery coreIdPeriodQuery(String coreAccountId, int offset) {
        return TransactionHistoryQuery.byCoreAccountId(coreAccountId, FROM, TO, offset, 2);
    }

    @Test
    void operatorStatementIsUnavailableWhenTheCellHasNoOperatorGateway() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CoreOperatorPort> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        StatementService withoutGateway = new StatementService(customers, port,
                serviceResolver(), new CountryProperties(Country.ZW),
                new StatementProperties(92, 4, 2, 30), absent, new SimpleMeterRegistry());

        assertThatThrownBy(() -> withoutGateway.statementForOperator(
                new OperatorCredential("Basic b3A6cHc="), "17", FROM, TO))
                .isInstanceOf(StatementUnavailableException.class);
    }

    @SuppressWarnings("unchecked")
    private CustomerNameResolver serviceResolver() {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return new CustomerNameResolver(provider,
                new ProfileCacheProperties(false, Duration.ofMinutes(5), 1000), new SimpleMeterRegistry());
    }

    private static TransactionPage fullPage(String idA, String idB) {
        return new TransactionPage(List.of(
                entry(idA, CREDIT, 100, 1_000L, FROM.plusDays(2)),
                entry(idB, CREDIT, 100, 1_100L, FROM.plusDays(2))), null);
    }
}
