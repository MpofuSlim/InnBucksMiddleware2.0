package zw.co.innbucks.middleware.auth.refresh;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The session-refresh block check: the decision (only a FULL block ends
 * sessions), fail-open on every read failure AND on a slow read (bounded wait,
 * a bounded pool, the abandoned read never interrupted), and the transaction
 * shape — the core is read with NO transaction open, the revocation runs
 * inside one. The Postgres half (families really revoked, the token unspent
 * while the core is read, the 403 over HTTP) is
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

    private final List<AccountBlockSessionGuard> guards = new ArrayList<>();
    /** Released in tearDown so no hung stub read outlives its test. */
    private final CountDownLatch release = new CountDownLatch(1);

    private AccountBlockSessionGuard guard;

    @BeforeEach
    void setUp() {
        customer.setId(UUID.randomUUID());
        customer.setCoreExternalId(customer.getId().toString());
        guard = guardWith(port);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        guards.forEach(AccountBlockSessionGuard::destroy);
        MDC.clear();
    }

    private AccountBlockSessionGuard guardWith(CoreBankingPort available) {
        return guardWith(available, new RefreshBlockCheckProperties(Duration.ofSeconds(5), 4));
    }

    @SuppressWarnings("unchecked")
    private AccountBlockSessionGuard guardWith(CoreBankingPort available, RefreshBlockCheckProperties properties) {
        ObjectProvider<CoreBankingPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        AccountBlockSessionGuard created = new AccountBlockSessionGuard(provider, refreshTokenService, audit,
                transactionManager, meters, properties);
        guards.add(created);
        return created;
    }

    /** A core that answers nothing until the test releases it (or 10 s pass). */
    private void coreHangs(AtomicBoolean interrupted, CountDownLatch finished) {
        when(port.listDepositAccountRefs(any())).thenAnswer(inv -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                interrupted.set(true);
            } finally {
                finished.countDown();
            }
            return List.of(account(customer.getId() + ":wallet", AccountRestriction.FULLY_BLOCKED));
        });
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
        // The read runs on the guard's own pool thread, so "no transaction on
        // the reading thread" is trivially true; what matters is that the
        // guard opens none while the read is in flight.
        AtomicInteger transactionsOpenDuringRead = new AtomicInteger(-1);
        AtomicBoolean revokedInsideTransaction = new AtomicBoolean(false);
        when(port.listDepositAccountRefs(any())).thenAnswer(inv -> {
            transactionsOpenDuringRead.set(transactionManager.begins - transactionManager.commits);
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
        assertThat(transactionsOpenDuringRead).hasValue(0);
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

    // ------------------------------------------------------- slow core

    @Test
    void aHungCoreReadIsAbandonedAtTheTimeoutAndTheRefreshGoesThrough() {
        // Fineract accepting connections but not answering: the adapter would
        // retry its 10 s read three times. A refresh must not wait for that —
        // nor be refused: the hung answer here would have been a FULL block,
        // and it is still let through, because an answer that has not arrived
        // is not an answer.
        AccountBlockSessionGuard bounded = guardWith(port,
                new RefreshBlockCheckProperties(Duration.ofMillis(200), 4));
        coreHangs(new AtomicBoolean(), new CountDownLatch(1));

        long started = System.nanoTime();
        assertThatCode(() -> bounded.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(200)).isLessThan(Duration.ofSeconds(3));
        verifyNoInteractions(refreshTokenService, audit);
        assertThat(count("timed_out")).isEqualTo(1.0);
    }

    @Test
    void theAbandonedReadIsNeverInterrupted() throws Exception {
        // Interrupting it would make the adapter count a merely-slow call as a
        // FAILURE on the circuit breaker payments share — refreshes could then
        // open the breaker on the money path. It finishes on its own instead.
        AccountBlockSessionGuard bounded = guardWith(port,
                new RefreshBlockCheckProperties(Duration.ofMillis(100), 4));
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        coreHangs(interrupted, finished);

        bounded.requireSessionAllowed(customer, DEVICE);

        // Still running well after the refresh gave up: not interrupted.
        assertThat(finished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isFalse();
        // Its late answer (a full block) changes nothing.
        verifyNoInteractions(refreshTokenService, audit);
    }

    @Test
    void withEverySlotBusyARefreshIsLetThroughWithoutQueueing() {
        // A stalled core holds the one slot; the next refresh must neither
        // queue behind it (it would then time out, not be "busy") nor add a
        // second read to the struggling core.
        AccountBlockSessionGuard oneSlot = guardWith(port,
                new RefreshBlockCheckProperties(Duration.ofMillis(300), 1));
        coreHangs(new AtomicBoolean(), new CountDownLatch(1));

        oneSlot.requireSessionAllowed(customer, DEVICE);
        assertThatCode(() -> oneSlot.requireSessionAllowed(customer, DEVICE)).doesNotThrowAnyException();

        verify(port, times(1)).listDepositAccountRefs(any());
        assertThat(count("timed_out")).isEqualTo(1.0);
        assertThat(count("busy")).isEqualTo(1.0);
        verifyNoInteractions(refreshTokenService, audit);
    }

    @Test
    void theReadCarriesTheCallersCorrelationIdAndLeavesNoneBehind() {
        // The adapter puts MDC's correlation id on the outbound call; a read
        // on a pool thread must still carry the refresh's — and must not leak
        // it into the next read that thread serves.
        AccountBlockSessionGuard oneSlot = guardWith(port,
                new RefreshBlockCheckProperties(Duration.ofSeconds(5), 1));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        when(port.listDepositAccountRefs(any())).thenAnswer(inv -> {
            seen.add(String.valueOf(MDC.get("correlationId")));
            return List.of(account(customer.getId() + ":wallet", AccountRestriction.NONE));
        });

        MDC.put("correlationId", "corr-refresh-1");
        oneSlot.requireSessionAllowed(customer, DEVICE);
        MDC.clear();
        oneSlot.requireSessionAllowed(customer, DEVICE);

        assertThat(seen).containsExactly("corr-refresh-1", "null");
    }

    @Test
    void unsetBoundsTakeTheDefaultsSoTheWaitIsNeverUnbounded() {
        RefreshBlockCheckProperties unset = new RefreshBlockCheckProperties(null, 0);
        assertThat(unset.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(unset.maxConcurrentReads()).isEqualTo(8);
        assertThat(new RefreshBlockCheckProperties(Duration.ofSeconds(-1), -3).timeout())
                .isEqualTo(Duration.ofSeconds(2));
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
                "unmapped", "read_failed", "timed_out", "busy")) {
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
        volatile int begins;
        volatile int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
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
