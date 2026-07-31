package zw.co.innbucks.middleware.transactions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.common.msisdn.InvalidMsisdnException;
import zw.co.innbucks.middleware.common.msisdn.MsisdnNormalizerRegistry;
import zw.co.innbucks.middleware.common.msisdn.ZimbabweMsisdnNormalizer;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties;
import zw.co.innbucks.middleware.ratelimit.RateLimitProperties.Limit;
import zw.co.innbucks.middleware.ratelimit.RateLimiterService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The oracle's guard rails: what a caller can learn, at what cost. The
 * indistinguishable-404 cases matter as much as the happy path — every
 * distinct miss reason is a bit of information an enumerator would pay for.
 */
class RecipientLookupServiceTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.fromString("9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d");
    private static final String MSISDN = "+263771234567";
    private static final String WALLET = RECIPIENT + ":wallet";

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RecipientLookupService service;

    private static RateLimitProperties limits(int lookupCapacity) {
        Limit generous = new Limit(1000, Duration.ofMinutes(1));
        return new RateLimitProperties(true, false, 0,
                generous, generous, generous, generous, generous, generous, generous,
                new Limit(lookupCapacity, Duration.ofMinutes(1)));
    }

    private RecipientLookupService build(int lookupCapacity) {
        RateLimitProperties properties = limits(lookupCapacity);
        MsisdnNormalizerRegistry registry = new MsisdnNormalizerRegistry(
                List.of(new ZimbabweMsisdnNormalizer()), new CountryProperties(Country.ZW));
        return new RecipientLookupService(customers, registry, new CountryProperties(Country.ZW),
                port, new RateLimiterService(properties), properties, meterRegistry);
    }

    @BeforeEach
    void setUp() {
        Customer recipient = new Customer();
        recipient.setId(RECIPIENT);
        recipient.setCountry("ZW");
        recipient.setMsisdn(MSISDN);
        recipient.setCoreExternalId(RECIPIENT.toString());
        when(customers.findByCountryAndMsisdn("ZW", MSISDN)).thenReturn(Optional.of(recipient));
        when(port.listDepositAccounts(new CoreCustomerRef(RECIPIENT.toString())))
                .thenReturn(List.of(new DepositAccountSummary(
                        new AccountRef(WALLET), "Wallet", "USD", new MinorUnits(5000, "USD"))));
        when(port.getProfile(new CoreCustomerRef(RECIPIENT.toString())))
                .thenReturn(new CustomerProfile(new CoreCustomerRef(RECIPIENT.toString()),
                        "Tariro", "Moyo", "ACTIVE"));
        service = build(1000);
    }

    @Test
    void resolvesALocalFormNumberToTheWalletAndAMaskedName() {
        RecipientView view = service.lookup(CALLER, "0771234567");

        assertThat(view.accountId()).isEqualTo(WALLET);
        assertThat(view.displayName()).isEqualTo("Tariro M.");
        // Normalised E.164 echoed so the confirm screen shows the real target.
        assertThat(view.msisdn()).isEqualTo(MSISDN);
    }

    @Test
    void neverReturnsTheFullSurname() {
        assertThat(service.lookup(CALLER, MSISDN).displayName()).doesNotContain("Moyo");
    }

    @Test
    void anUnknownNumberIsNotFound() {
        when(customers.findByCountryAndMsisdn(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup(CALLER, "0779999999"))
                .isInstanceOf(RecipientNotFoundException.class);
    }

    /**
     * All miss reasons must be ONE exception with ONE message — a caller who
     * can tell "not a customer" from "customer without a wallet yet" has
     * learned membership, which is the thing this endpoint must not sell.
     */
    @Test
    void everyMissReasonIsIndistinguishable() {
        when(customers.findByCountryAndMsisdn(anyString(), anyString())).thenReturn(Optional.empty());
        RecipientNotFoundException unknown = catchNotFound("0779999999");

        Customer unmapped = new Customer();
        unmapped.setId(RECIPIENT);
        unmapped.setCountry("ZW");
        unmapped.setMsisdn(MSISDN);
        when(customers.findByCountryAndMsisdn("ZW", MSISDN)).thenReturn(Optional.of(unmapped));
        RecipientNotFoundException noMapping = catchNotFound(MSISDN);

        unmapped.setCoreExternalId(RECIPIENT.toString());
        when(port.listDepositAccounts(any())).thenReturn(List.of());
        RecipientNotFoundException noAccount = catchNotFound(MSISDN);

        assertThat(unknown.getMessage())
                .isEqualTo(noMapping.getMessage())
                .isEqualTo(noAccount.getMessage());
    }

    private RecipientNotFoundException catchNotFound(String msisdn) {
        try {
            service.lookup(CALLER, msisdn);
            throw new AssertionError("expected RecipientNotFoundException");
        } catch (RecipientNotFoundException expected) {
            return expected;
        }
    }

    @Test
    void aMalformedNumberIsAValidationErrorNotANotFound() {
        // Shape is client-side knowledge; a typo must read as a typo.
        assertThatThrownBy(() -> service.lookup(CALLER, "12345"))
                .isInstanceOf(InvalidMsisdnException.class);
    }

    @Test
    void theBudgetIsPerCallerAndExhaustionAnswersBeforeAnyWork() {
        service = build(2);
        service.lookup(CALLER, MSISDN);
        service.lookup(CALLER, MSISDN);

        assertThatThrownBy(() -> service.lookup(CALLER, MSISDN))
                .isInstanceOf(RateLimitExceededException.class);
        // A DIFFERENT caller still has their own budget.
        assertThat(service.lookup(UUID.randomUUID(), MSISDN).accountId()).isEqualTo(WALLET);
    }

    @Test
    void aRateLimitedCallerLearnsNothingAboutTheNumber() {
        service = build(1);
        service.lookup(CALLER, MSISDN);   // spends the whole budget

        // Over budget, the limit fires FIRST — not invalid_msisdn, not
        // recipient_not_found — even for input that would have failed
        // validation, and no lookup work happens at all.
        assertThatThrownBy(() -> service.lookup(CALLER, "12345"))
                .isInstanceOf(RateLimitExceededException.class);
        verify(customers, org.mockito.Mockito.times(1)).findByCountryAndMsisdn(anyString(), anyString());
    }

    @Test
    void prefersTheWalletWhenTheRecipientHasSeveralAccounts() {
        when(port.listDepositAccounts(new CoreCustomerRef(RECIPIENT.toString())))
                .thenReturn(List.of(
                        new DepositAccountSummary(new AccountRef(RECIPIENT + ":savings"),
                                "Savings", "USD", new MinorUnits(1, "USD")),
                        new DepositAccountSummary(new AccountRef(WALLET),
                                "Wallet", "USD", new MinorUnits(2, "USD"))));

        assertThat(service.lookup(CALLER, MSISDN).accountId()).isEqualTo(WALLET);
    }

    @Test
    void maskingHandlesPartialNamesWithoutLeakingOrCrashing() {
        assertThat(RecipientLookupService.maskName("Tariro", "Moyo")).isEqualTo("Tariro M.");
        assertThat(RecipientLookupService.maskName("Tariro", null)).isEqualTo("Tariro");
        assertThat(RecipientLookupService.maskName(null, "Moyo")).isEqualTo("M.");
        assertThat(RecipientLookupService.maskName(null, null)).isEqualTo("InnBucks customer");
        assertThat(RecipientLookupService.maskName(" ", " ")).isEqualTo("InnBucks customer");
    }
}
