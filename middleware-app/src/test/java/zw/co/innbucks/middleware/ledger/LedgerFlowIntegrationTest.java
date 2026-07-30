package zw.co.innbucks.middleware.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.co.innbucks.middleware.corebanking.CoreBankingPort;
import zw.co.innbucks.middleware.corebanking.CoreCapability;
import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.command.CreateCustomerCommand;
import zw.co.innbucks.middleware.corebanking.command.MoneyMovementCommand;
import zw.co.innbucks.middleware.corebanking.command.TransferCommand;
import zw.co.innbucks.middleware.corebanking.exception.CoreUnknownOutcomeException;
import zw.co.innbucks.middleware.corebanking.value.AccountBalance;
import zw.co.innbucks.middleware.corebanking.value.AccountRef;
import zw.co.innbucks.middleware.corebanking.value.CoreCustomerRef;
import zw.co.innbucks.middleware.corebanking.value.CustomerProfile;
import zw.co.innbucks.middleware.corebanking.value.DepositAccountSummary;
import zw.co.innbucks.middleware.corebanking.value.IdempotencyKey;
import zw.co.innbucks.middleware.corebanking.value.MinorUnits;
import zw.co.innbucks.middleware.corebanking.value.TransactionLookup;
import zw.co.innbucks.middleware.corebanking.value.TransactionResult;
import zw.co.innbucks.middleware.corebanking.value.TransactionState;
import zw.co.innbucks.middleware.corebanking.value.TxRef;
import zw.co.innbucks.middleware.support.PostgresTestContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack ledger flow against real Postgres: write-ahead row, outcome
 * recording, journalled history, tamper-evident audit rows, reconciliation of
 * parked UNKNOWN rows, terminal immutability, and the unique external_ref
 * guard.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, LedgerFlowIntegrationTest.StubPortConfig.class})
class LedgerFlowIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    LedgeredMovementExecutor executor;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    LedgerReconciliationJob reconciliationJob;

    @Autowired
    StubCorePort stubPort;

    UUID customerId;

    /**
     * A settable CoreBankingPort stub: only getTransaction is exercised (the
     * executor takes the write call as a lambda); everything else fails loud.
     */
    static class StubCorePort implements CoreBankingPort {
        volatile Function<TransactionLookup, TransactionResult> onGetTransaction =
                lookup -> { throw new IllegalStateException("stub not configured"); };

        @Override public CoreProvider provider() { return CoreProvider.FINERACT; }
        @Override public Set<CoreCapability> capabilities() { return Set.of(); }
        @Override public CoreCustomerRef createCustomer(CreateCustomerCommand cmd, IdempotencyKey key) { throw new UnsupportedOperationException(); }
        @Override public CustomerProfile getProfile(CoreCustomerRef ref) { throw new UnsupportedOperationException(); }
        @Override public List<DepositAccountSummary> listDepositAccounts(CoreCustomerRef ref) { throw new UnsupportedOperationException(); }
        @Override public AccountBalance getBalance(AccountRef account) { throw new UnsupportedOperationException(); }
        @Override public TransactionResult deposit(MoneyMovementCommand cmd, IdempotencyKey key) { throw new UnsupportedOperationException(); }
        @Override public TransactionResult withdraw(MoneyMovementCommand cmd, IdempotencyKey key) { throw new UnsupportedOperationException(); }
        @Override public TransactionResult transfer(TransferCommand cmd, IdempotencyKey key) { throw new UnsupportedOperationException(); }
        @Override public AccountRef openDepositAccount(CoreCustomerRef customer, String requestedExternalId, IdempotencyKey key) { throw new UnsupportedOperationException(); }
        @Override public TransactionResult getTransaction(TransactionLookup lookup) { return onGetTransaction.apply(lookup); }
    }

    @TestConfiguration
    static class StubPortConfig {
        @Bean
        @Primary
        StubCorePort stubCorePort() {
            return new StubCorePort();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("TRUNCATE ledger_transaction_event, ledger_transaction, audit_event, customer CASCADE");
        customerId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO customer
                    (id, country, msisdn, pin_hash, kyc_tier, core_provider, core_external_id,
                     status, failed_pin_attempts, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?)
                """,
                customerId, "KE", "+254712000042", "basic", "FINERACT", customerId.toString(),
                "active", Timestamp.from(now), Timestamp.from(now));
    }

    private LedgerDraft draft(String externalRef) {
        return new LedgerDraft(customerId, LedgerTransactionType.TRANSFER,
                "SRC-ACC", "DST-ACC", new MinorUnits(2500L, "KES"),
                "integration test transfer", externalRef, CoreProvider.FINERACT);
    }

    private String statusOf(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transaction WHERE id = ?", String.class, id);
    }

    private List<String> journalOf(UUID id) {
        return jdbcTemplate.queryForList(
                "SELECT to_status FROM ledger_transaction_event WHERE transaction_id = ? ORDER BY id",
                String.class, id);
    }

    @Test
    void completedMovementIsLedgeredJournalledAndAudited() {
        LedgerOutcome outcome = executor.execute(draft("ref-completed-1"),
                ref -> new TransactionResult(new TxRef("CORE-1"), TransactionState.COMPLETED));

        assertThat(outcome.status()).isEqualTo(LedgerStatus.COMPLETED);
        assertThat(statusOf(outcome.transactionId())).isEqualTo("COMPLETED");
        assertThat(journalOf(outcome.transactionId())).containsExactly("PENDING", "COMPLETED");
        // Tamper-evident audit row with the REAL customer identity.
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'txn_completed' AND customer_id = ?",
                Integer.class, customerId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void unknownOutcomeParksThenReconcilerResolvesIt() {
        LedgerOutcome outcome = executor.execute(draft("ref-unknown-1"), ref -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, ref, "timeout mid-flight", null);
        });
        assertThat(outcome.status()).isEqualTo(LedgerStatus.UNKNOWN);
        assertThat(statusOf(outcome.transactionId())).isEqualTo("UNKNOWN");

        // The core, once queried, positively confirms the movement applied.
        stubPort.onGetTransaction =
                lookup -> new TransactionResult(new TxRef("CORE-RECON-1"), TransactionState.COMPLETED);
        reconciliationJob.sweep();

        assertThat(statusOf(outcome.transactionId())).isEqualTo("COMPLETED");
        assertThat(journalOf(outcome.transactionId()))
                .containsExactly("PENDING", "UNKNOWN", "COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT core_tx_ref FROM ledger_transaction WHERE id = ?",
                String.class, outcome.transactionId())).isEqualTo("CORE-RECON-1");
    }

    @Test
    void unresolvableRowStaysParkedWithBackoffNeverExpired() {
        LedgerOutcome outcome = executor.execute(draft("ref-unknown-2"), ref -> {
            throw new CoreUnknownOutcomeException(CoreProvider.FINERACT, ref, "reset after send", null);
        });
        stubPort.onGetTransaction =
                lookup -> new TransactionResult(lookup.externalRef(), TransactionState.UNKNOWN);

        reconciliationJob.sweep();

        assertThat(statusOf(outcome.transactionId())).isEqualTo("UNKNOWN");
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT reconcile_attempts FROM ledger_transaction WHERE id = ?",
                Integer.class, outcome.transactionId());
        assertThat(attempts).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_reconcile_at FROM ledger_transaction WHERE id = ?",
                Timestamp.class, outcome.transactionId())).isNotNull();

        // Second sweep before the backoff deadline: the row is not due, so the
        // (now booby-trapped) stub must not even be consulted.
        stubPort.onGetTransaction = lookup -> { throw new AssertionError("row was not due for polling"); };
        reconciliationJob.sweep();
        assertThat(statusOf(outcome.transactionId())).isEqualTo("UNKNOWN");
    }

    @Test
    void stalePendingIsParkedAsUnknownForReconciliation() {
        UUID id = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(600);
        jdbcTemplate.update("""
                INSERT INTO ledger_transaction
                    (id, customer_id, type, source_account, destination_account, amount_minor,
                     currency, external_ref, core_provider, status, created_at, updated_at, reconcile_attempts)
                VALUES (?, ?, 'WITHDRAWAL', 'SRC-ACC', NULL, 900, 'KES', 'ref-stale-1', 'FINERACT',
                        'PENDING', ?, ?, 0)
                """, id, customerId, Timestamp.from(old), Timestamp.from(old));
        // Any due-row polling in the same sweep must leave it parked.
        stubPort.onGetTransaction = lookup -> new TransactionResult(lookup.externalRef(), TransactionState.UNKNOWN);

        reconciliationJob.sweep();

        assertThat(statusOf(id)).isEqualTo("UNKNOWN");
        assertThat(journalOf(id)).contains("UNKNOWN");
    }

    @Test
    void terminalRowsRefuseFurtherTransitions() {
        LedgerOutcome outcome = executor.execute(draft("ref-terminal-1"),
                ref -> new TransactionResult(ref, TransactionState.COMPLETED));

        ledgerService.markFailed(outcome.transactionId(), "late", "must be refused");

        assertThat(statusOf(outcome.transactionId())).isEqualTo("COMPLETED");
        assertThat(journalOf(outcome.transactionId())).containsExactly("PENDING", "COMPLETED");
    }

    @Test
    void externalRefIsUniqueAcrossRows() {
        executor.execute(draft("ref-dup-1"),
                ref -> new TransactionResult(ref, TransactionState.COMPLETED));

        assertThatThrownBy(() -> executor.execute(draft("ref-dup-1"),
                ref -> new TransactionResult(ref, TransactionState.COMPLETED)))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
