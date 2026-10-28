package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.kyc.KycSubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code kyc_submission} row. {@code expiresAt} is set exactly while the
 * CUSTOMER owes work on an open round: a draft, or a rejection that reopened
 * the next round for the files listed in its redo.
 */
public record KycSubmissionRecord(
        UUID id,
        UUID customerId,
        KycTier targetTier,
        KycSubmissionStatus status,
        int currentRound,
        /** AES-GCM envelope of the typed address (enhanced only); never logged. */
        String addressCiphertext,
        Instant createdAt,
        Instant submittedAt,
        Instant decidedAt,
        Instant expiresAt,
        Instant updatedAt) {

    /**
     * The customer is still working on the current round: a draft, or a
     * rejected submission whose next round was reopened for redo.
     */
    public boolean acceptingFiles() {
        return status == KycSubmissionStatus.DRAFT
                || status == KycSubmissionStatus.REJECTED && expiresAt != null;
    }

    /**
     * Counts against "one open submission per customer" — the same predicate
     * as the partial unique index {@code uq_kyc_submission_one_open}.
     */
    public boolean open() {
        return acceptingFiles() || status == KycSubmissionStatus.PENDING_REVIEW;
    }

    /** Past its deadline but not yet swept by the lifecycle job. */
    public boolean lapsed(Instant now) {
        return acceptingFiles() && expiresAt != null && !now.isBefore(expiresAt);
    }

    public KycSubmissionRecord withState(KycSubmissionStatus newStatus, int round, Instant newSubmittedAt,
                                         Instant newDecidedAt, Instant newExpiresAt, Instant now) {
        return new KycSubmissionRecord(id, customerId, targetTier, newStatus, round, addressCiphertext,
                createdAt, newSubmittedAt, newDecidedAt, newExpiresAt, now);
    }

    @Override
    public String toString() {
        return "KycSubmissionRecord[id=" + id + ", customerId=" + customerId + ", targetTier=" + targetTier
                + ", status=" + status + ", round=" + currentRound + ", hasAddress=" + (addressCiphertext != null)
                + ", expiresAt=" + expiresAt + "]";
    }
}
