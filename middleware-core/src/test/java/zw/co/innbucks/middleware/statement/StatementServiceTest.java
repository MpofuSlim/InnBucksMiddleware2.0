package zw.co.innbucks.middleware.statement;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
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

        // pageSize 2 so the paging loop is actually exercised.
        service = new StatementService(customers, port, resolver,
                new CountryProperties(Country.ZW), new StatementProperties(92, 4, 2),
                new SimpleMeterRegistry());
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
                    assertThat(((StatementRequestException) ex).tooLarge()).isTrue();
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

    private static TransactionPage fullPage(String idA, String idB) {
        return new TransactionPage(List.of(
                entry(idA, CREDIT, 100, 1_000L, FROM.plusDays(2)),
                entry(idB, CREDIT, 100, 1_100L, FROM.plusDays(2))), null);
    }
}
