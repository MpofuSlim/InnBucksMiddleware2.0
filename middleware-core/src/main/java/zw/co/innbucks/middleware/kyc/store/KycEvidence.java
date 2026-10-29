package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;

import java.util.UUID;

/**
 * One line of a round's evidence: for {@code kind}, this round shows file
 * {@code fileId}, which was uploaded in round {@code fileRound}. A
 * {@code fileRound} earlier than the evidence's own round is a file CARRIED
 * FORWARD by reference after a rejection — same row, never copied bytes.
 */
public record KycEvidence(KycFileKind kind, UUID fileId, int fileRound) {
}
