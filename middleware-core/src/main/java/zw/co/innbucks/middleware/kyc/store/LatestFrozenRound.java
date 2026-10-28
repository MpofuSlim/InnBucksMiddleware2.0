package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;

import java.util.Set;

/**
 * What the customer last sent for review (any submission, any decision):
 * the masked ID, whether that submission carried an address, and which files
 * the round still holds (a rejected round's files disappear from here once
 * their retention runs out).
 */
public record LatestFrozenRound(String maskedId, boolean hasAddress, Set<KycFileKind> kinds) {
}
