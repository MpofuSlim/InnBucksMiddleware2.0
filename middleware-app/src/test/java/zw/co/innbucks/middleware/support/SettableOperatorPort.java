package zw.co.innbucks.middleware.support;

import zw.co.innbucks.middleware.corebanking.CoreOperatorPort;
import zw.co.innbucks.middleware.corebanking.value.OperatorAccountView;
import zw.co.innbucks.middleware.corebanking.value.OperatorCredential;

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

    @Override
    public OperatorAccountView authorizeAndDescribeAccount(OperatorCredential credential,
                                                           String savingsAccountId) {
        return onAuthorize.apply(credential, savingsAccountId);
    }
}
