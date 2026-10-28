package zw.co.innbucks.middleware.auth.refresh;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.auth.exception.AccountRestrictedSessionException;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreAuthException;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;
import zw.co.innbucks.middleware.corebanking.exception.CoreTransientException;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.AccountRestriction;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountRef;
import zw.co.innbucks.middleware.customer.Customer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The session-refresh block check: the decision (only a FULL block ends
 * sessions), fail-open on every read failure, and the transaction shape — the
 * core is read with NO transaction open, the revocation runs inside one. The
 * Postgres half (families really revoked, the 403 over HTTP) is
 * {@code RefreshAccountBlockIntegrationTest}.
 */
class AccountBlockSessionGuardTest {

    private static final String DEVICE = "device-1";

    private final CoreBankingPort port = mock(CoreBankingPort.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final AuditService audit = mock(AuditService.class);
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Customer customer = new Customer();

    private AccountBlockSessionGuard guard;

    @BeforeEach
    void setUp() {
        customer.setId(UUID.randomUUID());
        customer.setCoreExternalId(customer.getId().toString());
        guard = guardWith(port);
    }

    @SuppressWarnings("unchecked")
    private AccountBlockSessionGuard guardWith(CoreBankingPort available) {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new AccountBlockSessionGuard(provider, refreshTokenService, audit, transactionManager, meters);
    }

    private static DepositAccountRef account(String id, AccountRestriction restriction) {
        return new DepositAccountRef(new AccountRef(id), "InnBucks Wallet", "USD", "000000010", restriction);
    }

    private void walletIs(AccountRestriction restriction) {
        when(port.listDepositAccountRefs(new CoreCustomerRef(customer.getCoreExternalId())))
                .thenReturn(List.of(account(customer.getId() + ":wallet", restriction)));
    }

    private double count(String outcome) {
        return meters.counter("innbucks.auth.refresh.block_check", "outcome", outcome).count();
    }

    // ------------------------------------------------------------ decision

    @Test
    void onlyAFullBlockOnAnyAccountEndsTheSession() {
        assertThat(AccountBlockSessionGuard.assess(List.of(
                account("a", AccountRestriction.NONE),
                account("b", AccountRestriction.FULLY_BLOCKED))))
                .isEqualTo(AccountBlockSessionGuard.Outcome.FULLY_BLOCKED);
        assertThat(AccountBlockSessionGuard.assess(List.of(
                account("a", AccountRestriction.CREDITS_BLOCKED),
                account("b", AccountRestriction.DEBITS_BLOCKED))))
                .isEqualTo(AccountBlockSessionGuard.Outcome.DEBITS_BLOCKED);
        assertThat(AccountBlockSessionGuard.assess(List.of(account("a", AccountRestriction.CREDITS_BLOCKED))))
                .isEqualTo(AccountBlockSessionGuard.Outcome.CREDITS_BLOCKED);
        assertThat(AccountBlockSessionGuard.assess(List.of(account("a", AccountRestriction.NONE))))
                .isEqualTo(AccountBlockSessionGuard.Outcome.NONE);
        assertThat(AccountBlockSessionGuard.assess(List.of()))
                .isEqualTo(AccountBlockSessionGuard.Outcome.NONE);
    }

    // ------------------------------------------------------------ full block

    @Test
    void aFullBlockRevokesEverySessionInItsOwnTransactionAuditsAndRefuses() {
        AtomicBoolean readInsideTransaction = new AtomicBoolean(true);
        AtomicBoolean revokedInsideTransaction = new AtomicBoolean(false);
        when(port.listDepositAccountRefs(any())).thenAnswer(inv -> {
            readInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(account(customer.getId() + ":wallet", AccountRestriction.FULLY_BLOCKED));
        });
        when(refreshTokenService.revokeAllSessions(customer.getId(), "account_blocked")).thenAnswer(inv -> {
            revokedInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 3;
        });

        assertThatThrownBy(() -> guard.requireSessionAllowed(customer, DEVICE))
                .isInstanceOf(AccountRestrictedSessionException.class);

        // The core call (HTTP, ~100 ms or a timeout) never holds a pooled
        // connection; the revocation, which MUST have a transaction, gets one.
        assertThat(readInsideTransaction).isFalse();
        assertThat(revokedInsideTransaction).isTrue();
        assertThat(transactionManager.commits).isEqualTo(1);
        verify(audit).record(AuditAction.REFRESH_BLOCKED_ACCOUNT_RESTRICTED, AuditOutcome.FAILURE,
                customer.getId(), DEVICE, Map.of("refreshTokensRevoked", 3));
        assertThat(count("fully_blocked")).isEqualTo(1.0);
    }

    // ------------------------------------------------- blocks that do not sign out

    @Test
    void aDebitOnlyBlockKeepsTheSession() {
        // The bank's "pause payments" lever: the core already refuses every
        // payment out, and the customer may still need balances, statements
        // and incoming money. Signing out would make a pause a lockout.
        walletIs(AccountRestriction.DEBITS_BLOCKED);

        assertThatCode(() -> guard.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        verifyNoInteractions(refreshTokenService, audit);
        assertThat(count("debits_blocked")).isEqualTo(1.0);
    }

    @Test
    void aCreditOnlyBlockKeepsTheSession() {
        walletIs(AccountRestriction.CREDITS_BLOCKED);

        assertThatCode(() -> guard.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        verifyNoInteractions(refreshTokenService, audit);
        assertThat(count("credits_blocked")).isEqualTo(1.0);
    }

    @Test
    void anUnblockedAccountKeepsTheSession() {
        walletIs(AccountRestriction.NONE);

        assertThatCode(() -> guard.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        verifyNoInteractions(refreshTokenService, audit);
        assertThat(count("none")).isEqualTo(1.0);
    }

    // ----------------------------------------------------------- fail open

    @Test
    void everyKindOfReadFailureFailsOpenAndIsCounted() {
        // Fineract down, our credential refused, a provisioning fault in the
        // listing, a bug: none may sign anyone out. The money is already
        // blocked in the core whatever this check concludes.
        for (RuntimeException failure : List.<RuntimeException>of(
                new CoreTransientException(CoreProvider.FINERACT, "Fineract unreachable", null),
                new CoreServerException(CoreProvider.FINERACT, "Fineract read failed (500)", null),
                new CoreAuthException(CoreProvider.FINERACT, "Fineract rejected the read credentials", null),
                new IllegalStateException("unexpected"))) {
            doThrow(failure).when(port).listDepositAccountRefs(any());

            assertThatCode(() -> guard.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();
        }

        verify(refreshTokenService, never()).revokeAllSessions(any(), anyString());
        verifyNoInteractions(audit);
        assertThat(count("read_failed")).isEqualTo(4.0);
    }

    @Test
    void noPortInTheContextFailsOpen() {
        AccountBlockSessionGuard withoutPort = guardWith(null);

        assertThatCode(() -> withoutPort.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        assertThat(count("read_failed")).isEqualTo(1.0);
    }

    @Test
    void aCustomerWithNoCoreMappingIsSkippedWithoutACoreCall() {
        customer.setCoreExternalId(null);

        assertThatCode(() -> guard.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        verifyNoInteractions(port, refreshTokenService, audit);
        assertThat(count("unmapped")).isEqualTo(1.0);
    }

    @Test
    void everyOutcomeIsRegisteredAtZeroSoAlertsSeeTheFirstIncrease() {
        for (String outcome : List.of("none", "debits_blocked", "credits_blocked", "fully_blocked",
                "unmapped", "read_failed")) {
            assertThat(meters.find("innbucks.auth.refresh.block_check").tag("outcome", outcome).counter())
                    .as(outcome).isNotNull();
        }
    }

    @Test
    void theRevocationReasonFitsTheColumnAndNamesTheCause() {
        // refresh_token.revocation_reason is VARCHAR(64) (V1).
        assertThat(AccountBlockSessionGuard.REVOCATION_REASON).isEqualTo("account_blocked").hasSizeLessThan(64);
    }

    /**
     * A real {@link AbstractPlatformTransactionManager} with no resource, so
     * {@link TransactionSynchronizationManager#isActualTransactionActive()}
     * reports exactly what Spring would — the property under test.
     */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
