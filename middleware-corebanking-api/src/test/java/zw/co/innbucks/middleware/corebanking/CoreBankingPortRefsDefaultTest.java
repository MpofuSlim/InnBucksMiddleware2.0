package zw.co.innbucks.middleware.corebanking;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link CoreBankingPort#listDepositAccountRefs} DEFAULT — what an adapter
 * that has not overridden it gets. It must be CORRECT (never a stub, never
 * empty), because an adapter author who forgets the override should ship a slow
 * service, not a broken ownership check that silently lets nobody through — or,
 * worse, everybody.
 *
 * <p>The Fineract adapter overrides it, so nothing in production runs this
 * path today; the Veengu adapter will inherit it on day one.
 */
class CoreBankingPortRefsDefaultTest {

    private static final CoreCustomerRef CUSTOMER =
            new CoreCustomerRef("9a8b7c6d-5e4f-4a3b-2c1d-0e9f8a7b6c5d");

    @Test
    void theDefaultDerivesEveryRefFromTheFullListing() {
        CoreBankingPort port = portListing(List.of(
                new DepositAccountSummary(new AccountRef("acct-1"), "Wallet", "USD",
                        new MinorUnits(5000, "USD")),
                new DepositAccountSummary(new AccountRef("acct-2"), "Savings", "USD",
                        new MinorUnits(0, "USD"))));

        List<DepositAccountRef> refs = port.listDepositAccountRefs(CUSTOMER);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).account().externalId()).isEqualTo("acct-1");
        assertThat(refs.get(0).name()).isEqualTo("Wallet");
        assertThat(refs.get(0).currencyCode()).isEqualTo("USD");
        assertThat(refs.get(1).account().externalId()).isEqualTo("acct-2");
    }

    /**
     * An account number is display-only and the full listing does not carry
     * one, so the default leaves it null rather than inventing a value or
     * making an extra call to find out. Callers must treat it as optional.
     */
    @Test
    void theDefaultLeavesTheAccountNumberNullRatherThanGuessing() {
        CoreBankingPort port = portListing(List.of(
                new DepositAccountSummary(new AccountRef("acct-1"), "Wallet", "USD",
                        new MinorUnits(5000, "USD"))));

        assertThat(port.listDepositAccountRefs(CUSTOMER).get(0).accountNumber()).isNull();
    }

    @Test
    void aCustomerWithNoAccountsIsEmptyNotNull() {
        assertThat(portListing(List.of()).listDepositAccountRefs(CUSTOMER)).isEmpty();
    }

    private static CoreBankingPort portListing(List<DepositAccountSummary> accounts) {
        return new CoreBankingPort() {
            @Override
            public List<DepositAccountSummary> listDepositAccounts(CoreCustomerRef ref) {
                return accounts;
            }

            @Override public CoreProvider provider() { return CoreProvider.FINERACT; }
            @Override public Set<CoreCapability> capabilities() { return Set.of(); }
            @Override public CoreCustomerRef createCustomer(CreateCustomerCommand cmd, IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }
            @Override public AccountRef openDepositAccount(CoreCustomerRef customer, String requestedExternalId,
                                                           IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }
            @Override public CustomerProfile getProfile(CoreCustomerRef ref) {
                throw new UnsupportedOperationException();
            }
            @Override public AccountBalance getBalance(AccountRef account) {
                throw new UnsupportedOperationException();
            }
            @Override public TransactionResult deposit(MoneyMovementCommand cmd, IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }
            @Override public TransactionResult withdraw(MoneyMovementCommand cmd, IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }
            @Override public TransactionResult transfer(TransferCommand cmd, IdempotencyKey key) {
                throw new UnsupportedOperationException();
            }
            @Override public TransactionResult getTransaction(TransactionLookup lookup) {
                throw new UnsupportedOperationException();
            }
            @Override public TransactionPage listTransactions(TransactionHistoryQuery query) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
