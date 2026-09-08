package zw.co.innbucks.middleware.support;

import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.value.LoanTransactionPage;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;
import zw.co.innbucks.middleware.corebanking.value.OperatorLoanView;

import java.util.function.BiFunction;

/**
 * Test stand-in for {@link CoreOperatorPort}, same idea as
 * {@link SettableCorePort}: each case assigns the behaviour it needs.
 */
public class SettableOperatorPort implements CoreOperatorPort {

    public volatile BiFunction<OperatorCredential, String, OperatorAccountView> onAuthorize =
            (credential, accountId) -> {
                throw new IllegalStateException("stub onAuthorize not configured");
            };

    public volatile BiFunction<OperatorCredential, String, OperatorLoanView> onAuthorizeLoan =
            (credential, loanId) -> {
                throw new IllegalStateException("stub onAuthorizeLoan not configured");
            };

    public volatile LoanPageFunction onListLoanTransactions =
            (credential, loanId, page, pageSize) -> {
                throw new IllegalStateException("stub onListLoanTransactions not configured");
            };

    @FunctionalInterface
    public interface LoanPageFunction {
        LoanTransactionPage apply(OperatorCredential credential, String loanId, int page, int pageSize);
    }

    @Override
    public OperatorAccountView authorizeAndDescribeAccount(OperatorCredential credential,
                                                           String savingsAccountId) {
        return onAuthorize.apply(credential, savingsAccountId);
    }

    @Override
    public OperatorLoanView authorizeAndDescribeLoan(OperatorCredential credential, String loanId) {
        return onAuthorizeLoan.apply(credential, loanId);
    }

    @Override
    public LoanTransactionPage listLoanTransactions(OperatorCredential credential, String loanId,
                                                    int page, int pageSize) {
        return onListLoanTransactions.apply(credential, loanId, page, pageSize);
    }
}
