package zw.co.innbucks.middleware.ledger;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One money movement's ledger row. INSERTed via JdbcTemplate (client-assigned
 * {@code @Id} — the Spring Data JDBC save()-emits-UPDATE gotcha); loaded rows
 * are UPDATEd through the repository inside {@link LedgerService#transition}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("ledger_transaction")
public class LedgerTransaction {

    @Id
    private UUID id;

    private UUID customerId;

    private String type;

    /** Core-side account externalIds. Source is null for DEPOSIT, destination null for WITHDRAWAL. */
    private String sourceAccount;

    private String destinationAccount;

    private long amountMinor;

    private String currency;

    private String narrative;

    /**
     * The middleware-minted reference sent to the core with the write — the
     * reconciliation handle. Unique; persisted BEFORE the call goes out.
     */
    private String externalRef;

    private String coreProvider;

    /** The core's own reference for the transaction, when it echoes one. */
    private String coreTxRef;

    private String status;

    private String failureCode;

    private String failureMessage;

    private String correlationId;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant completedAt;

    private int reconcileAttempts;

    private Instant nextReconcileAt;

    public LedgerStatus statusEnum() {
        return LedgerStatus.valueOf(status);
    }

    public LedgerTransactionType typeEnum() {
        return LedgerTransactionType.valueOf(type);
    }
}
