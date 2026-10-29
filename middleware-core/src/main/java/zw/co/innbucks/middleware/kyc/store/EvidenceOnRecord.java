package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;

import java.util.Set;

/**
 * The customer's evidence of record: what their latest APPROVED round holds —
 * the masked ID, whether that request carried an address, and which files the
 * round shows. Approved rounds' files are never deleted, so this cannot change
 * under the customer's feet the way a pending or rejected round's can (a
 * rejected round's evidence goes when its retention runs out).
 */
public record EvidenceOnRecord(String maskedId, boolean hasAddress, Set<KycFileKind> kinds) {
}
