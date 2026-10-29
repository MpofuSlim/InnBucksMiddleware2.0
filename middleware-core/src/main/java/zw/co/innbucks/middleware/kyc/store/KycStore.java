package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.KycRejectionReason;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Submission, round and evidence state (everything but the image bytes, which
 * live behind {@link KycFileStore}). Callers own the transactions: the
 * {@code lock*} reads take the submission row {@code FOR UPDATE} and only mean
 * something inside one.
 */
public interface KycStore {

    Optional<KycSubmissionRecord> findSubmission(UUID submissionId);

    /** {@code SELECT … FOR UPDATE} on the submission row. */
    Optional<KycSubmissionRecord> lockSubmission(UUID submissionId);

    /** The customer's open submission (see {@link KycSubmissionRecord#open()}), if any. */
    Optional<KycSubmissionRecord> findOpen(UUID customerId);

    /** {@link #findOpen} with the row locked. */
    Optional<KycSubmissionRecord> lockOpen(UUID customerId);

    /** The customer's most recently created submission, whatever its status. */
    Optional<KycSubmissionRecord> findLatest(UUID customerId);

    /**
     * @throws org.springframework.dao.DuplicateKeyException when the customer
     *         already has an open submission (the partial unique index)
     */
    void insertSubmission(KycSubmissionRecord submission);

    /** Writes status, current round, submitted/decided/expires and updated timestamps. */
    void updateSubmission(KycSubmissionRecord submission);

    void insertRound(KycRoundRecord round);

    Optional<KycRoundRecord> findRound(UUID submissionId, int round);

    /** The most recent round of the submission that carries a decision. */
    Optional<KycRoundRecord> findLatestDecidedRound(UUID submissionId);

    /**
     * The customer's evidence of record: their most recently APPROVED round
     * across all submissions, with its evidence. Empty until one is approved.
     */
    Optional<EvidenceOnRecord> findEvidenceOnRecord(UUID customerId);

    void freezeRound(UUID submissionId, int round, Instant frozenAt, int duplicateCount);

    void recordDecision(UUID submissionId, int round, String decision, Instant decidedAt,
                        List<KycRejectionReason> reasons, Set<KycFileKind> redo);

    List<KycEvidence> evidence(UUID submissionId, int round);

    /**
     * Points {@code (submission, round, kind)} at {@code fileId}.
     *
     * @return the file it pointed at before, if any
     */
    Optional<UUID> putEvidence(UUID submissionId, int round, KycFileKind kind, UUID fileId);

    /** Carries {@code kinds} of {@code fromRound} into {@code toRound} by reference (same file rows). */
    void carryForward(UUID submissionId, int fromRound, int toRound, Set<KycFileKind> kinds);

    int deleteEvidence(UUID submissionId, int round);

    /** Files of the submission that no round's evidence references any more. */
    List<UUID> unreferencedFileIds(UUID submissionId);

    /**
     * Distinct OTHER customers holding this ID: by their sign-up hash (any of
     * {@code signupHashes} — the sign-up hash was taken over the number as
     * typed) or by the canonical HMAC of a KYC round they SUBMITTED (frozen).
     * A draft that was never sent — still open, cancelled or lapsed — is not
     * a claim to the number: counting it flagged the real holder over someone
     * else's typo, and with cancel it let any account plant a flag against a
     * known number for the cost of a create and a cancel.
     */
    int countOtherCustomersWithNationalId(UUID customerId, Collection<String> signupHashes, String canonicalHmac);

    /** Submissions whose open round passed its deadline. */
    List<UUID> findLapsed(Instant now, int limit);

    /** Rejected rounds decided before {@code decidedBefore} whose files are not purged yet. */
    List<RoundRef> findRejectedRoundsToPurge(Instant decidedBefore, int limit);

    void markFilesPurged(UUID submissionId, int round, Instant at);

    /** A (submission, round) pair. */
    record RoundRef(UUID submissionId, int round) {
    }
}
