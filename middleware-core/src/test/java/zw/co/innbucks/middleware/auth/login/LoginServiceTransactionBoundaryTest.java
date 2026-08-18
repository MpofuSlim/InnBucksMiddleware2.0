package zw.co.innbucks.middleware.auth.login;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.middleware.customer.CustomerLockoutStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the connection-hold half of the lockout fix — otherwise untestable, and
 * a one-word regression away.
 *
 * <p>THE INVARIANT: no pooled connection may be held across the Argon2id
 * compare in {@code LoginService.login}. The mechanism is that
 * {@code DataSourceTransactionManager.doBegin} acquires the connection EAGERLY
 * at transaction start — before the first line of the method body — and only
 * releases it after the method returns or throws (there is no
 * {@code LazyConnectionDataSourceProxy} in this repo). So a single
 * {@code @Transactional} anywhere on this path pins one connection per
 * in-flight login for ~100-300ms of pure CPU, and the pool drains under a
 * burst of sign-ins.
 *
 * <p>Reflection is deliberately the ONLY pin proposed for this property.
 * Asserting pool occupancy from a second thread is timing-dependent and the
 * classic CI flake.
 *
 * <p>Honest about what it does NOT catch: someone wrapping {@code login()} in a
 * {@code TransactionTemplate}, or adding a transactional collaborator call
 * before the hash. It catches the regression that the code actually invites —
 * re-adding the annotation.
 */
class LoginServiceTransactionBoundaryTest {

    @Test
    void loginOwnsNoTransaction() throws Exception {
        assertThat(LoginService.class
                .getDeclaredMethod("login", String.class, String.class, String.class)
                .getAnnotation(Transactional.class))
                .as("login() must not be @Transactional — it would hold a pooled connection across Argon2id")
                .isNull();

        assertThat(LoginService.class.getAnnotation(Transactional.class))
                .as("LoginService must not be class-level @Transactional either")
                .isNull();
    }

    @Test
    void theLockoutStatementsOwnNoTransactionEither() throws Exception {
        assertThat(CustomerLockoutStore.class.getAnnotation(Transactional.class))
                .as("a lone JDBC statement is already atomic; an annotation only re-pins a connection")
                .isNull();

        for (String method : new String[]{"recordFailedAttempt", "clearFailureCounters"}) {
            assertThat(java.util.Arrays.stream(CustomerLockoutStore.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(method))
                    .peek(m -> assertThat(m.getAnnotation(Transactional.class))
                            .as(method + "() must not be @Transactional")
                            .isNull())
                    .count())
                    .as(method + " exists on CustomerLockoutStore")
                    .isEqualTo(1);
        }
    }
}
