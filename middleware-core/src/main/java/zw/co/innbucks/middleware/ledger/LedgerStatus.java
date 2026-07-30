package zw.co.innbucks.middleware.ledger;

/**
 * Lifecycle of a money movement in the local ledger. The write-ahead rule:
 * a {@link #PENDING} row is committed BEFORE the core-banking call goes out,
 * so an upstream success can never exist without a local row.
 *
 * <ul>
 *   <li>{@link #PENDING} — row opened; the core call has not resolved yet.</li>
 *   <li>{@link #SUBMITTED} — the core POSITIVELY accepted the write and is
 *       processing it asynchronously; the reconciler polls to a terminal
 *       state.</li>
 *   <li>{@link #COMPLETED} / {@link #FAILED} — terminal, immutable.</li>
 *   <li>{@link #UNKNOWN} — the write's fate is unprovable (timeout
 *       mid-flight, crash between open and outcome, unclassifiable error).
 *       Parked for the reconciler; NEVER guessed, NEVER auto-expired —
 *       a blocked row beats a double charge.</li>
 * </ul>
 */
public enum LedgerStatus {
    PENDING,
    SUBMITTED,
    COMPLETED,
    FAILED,
    UNKNOWN;

    public String dbValue() {
        return name();
    }
}
