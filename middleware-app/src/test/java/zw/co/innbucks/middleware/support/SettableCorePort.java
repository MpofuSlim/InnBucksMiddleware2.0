package zw.co.innbucks.middleware.support;

import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreCapability;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import zw.co.innbucks.middleware.corebanking.value.TransactionHistoryQuery;
import zw.co.innbucks.middleware.corebanking.value.TransactionPage;

/**
 * Fully settable {@link CoreBankingPort} stub for integration tests: each
 * operation delegates to a swappable function; anything a test didn't
 * configure fails loud instead of silently succeeding.
 */
public class SettableCorePort implements CoreBankingPort {

    public volatile BiFunction<CreateCustomerCommand, IdempotencyKey, CoreCustomerRef> onCreateCustomer =
            (cmd, key) -> { throw new IllegalStateException("stub onCreateCustomer not configured"); };
    public volatile Function<CoreCustomerRef, CustomerProfile> onGetProfile =
            ref -> { throw new IllegalStateException("stub onGetProfile not configured"); };
    public volatile Function<CoreCustomerRef, List<DepositAccountSummary>> onListAccounts =
            ref -> { throw new IllegalStateException("stub onListAccounts not configured"); };
    public volatile Function<AccountRef, AccountBalance> onGetBalance =
            ref -> { throw new IllegalStateException("stub onGetBalance not configured"); };
    public volatile BiFunction<MoneyMovementCommand, IdempotencyKey, TransactionResult> onDeposit =
            (cmd, key) -> { throw new IllegalStateException("stub onDeposit not configured"); };
    public volatile BiFunction<MoneyMovementCommand, IdempotencyKey, TransactionResult> onWithdraw =
            (cmd, key) -> { throw new IllegalStateException("stub onWithdraw not configured"); };
    public volatile BiFunction<TransferCommand, IdempotencyKey, TransactionResult> onTransfer =
            (cmd, key) -> { throw new IllegalStateException("stub onTransfer not configured"); };
    public volatile Function<TransactionLookup, TransactionResult> onGetTransaction =
            lookup -> { throw new IllegalStateException("stub onGetTransaction not configured"); };
    /** Defaults to an empty page — unlike the others, a statement has a sensible
     *  no-op so tests that never exercise it don't have to configure it. */
    public volatile Function<TransactionHistoryQuery, TransactionPage> onListTransactions =
            query -> new TransactionPage(List.of(), 0L);
    public volatile TriFunction<CoreCustomerRef, String, IdempotencyKey, AccountRef> onOpenDepositAccount =
            (customer, extId, key) -> { throw new IllegalStateException("stub onOpenDepositAccount not configured"); };

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    @Override public CoreProvider provider() { return CoreProvider.FINERACT; }
    @Override public Set<CoreCapability> capabilities() {
        return Set.of(CoreCapability.SERVER_SIDE_DEDUP, CoreCapability.CLIENT_ASSIGNED_EXTERNAL_ID);
    }
    @Override public CoreCustomerRef createCustomer(CreateCustomerCommand cmd, IdempotencyKey key) {
        return onCreateCustomer.apply(cmd, key);
    }
    @Override public AccountRef openDepositAccount(CoreCustomerRef customer, String requestedExternalId, IdempotencyKey key) {
        return onOpenDepositAccount.apply(customer, requestedExternalId, key);
    }
    @Override public CustomerProfile getProfile(CoreCustomerRef ref) { return onGetProfile.apply(ref); }
    @Override public List<DepositAccountSummary> listDepositAccounts(CoreCustomerRef ref) { return onListAccounts.apply(ref); }
    @Override public AccountBalance getBalance(AccountRef account) { return onGetBalance.apply(account); }
    @Override public TransactionResult deposit(MoneyMovementCommand cmd, IdempotencyKey key) { return onDeposit.apply(cmd, key); }
    @Override public TransactionResult withdraw(MoneyMovementCommand cmd, IdempotencyKey key) { return onWithdraw.apply(cmd, key); }
    @Override public TransactionResult transfer(TransferCommand cmd, IdempotencyKey key) { return onTransfer.apply(cmd, key); }
    @Override public TransactionResult getTransaction(TransactionLookup lookup) { return onGetTransaction.apply(lookup); }
    @Override public TransactionPage listTransactions(TransactionHistoryQuery q) { return onListTransactions.apply(q); }
}
