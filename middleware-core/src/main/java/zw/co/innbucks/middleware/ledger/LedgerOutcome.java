package zw.co.innbucks.middleware.ledger;

import java.util.UUID;

/**
 * What a ledgered core call resolved to. {@code UNKNOWN} is a handled
 * outcome, not an error — the caller renders it as PROCESSING and the
 * reconciler owns resolution.
 */
public record LedgerOutcome(UUID transactionId, LedgerStatus status, String coreTxRef) {
}
