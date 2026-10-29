package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.KycRejectionReason;
import zw.co.innbucks.middleware.kyc.SignupMatch;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One {@code kyc_round} row: the evidence one reviewer decision is about.
 * Frozen ({@code frozenAt}) when the customer submits it — from then on its
 * ID and files never change, so whoever decides it sees exactly what was
 * submitted.
 */
public record KycRoundRecord(
        UUID submissionId,
        int round,
        Instant openedAt,
        Instant frozenAt,
        /** AES-GCM envelope of the canonical ID, bound to (submission, round). */
        String nationalIdCiphertext,
        /** HMAC-SHA256 of the canonical ID under the national-ID key: the duplicate-search key. */
        String nationalIdHmac,
        String maskedId,
        SignupMatch signupMatch,
        /** Other customers with the same ID; computed when the round is frozen, null before. */
        Integer duplicateCount,
        /** {@code approved} / {@code rejected}, set by the review step; null while undecided. */
        String decision,
        Instant decidedAt,
        List<KycRejectionReason> rejectionReasons,
        Set<KycFileKind> redo,
        Instant filesPurgedAt) {

    public boolean frozen() {
        return frozenAt != null;
    }

    @Override
    public String toString() {
        return "KycRoundRecord[submissionId=" + submissionId + ", round=" + round + ", frozen=" + frozen()
                + ", maskedId=" + maskedId + ", signupMatch=" + signupMatch + ", duplicateCount=" + duplicateCount
                + ", decision=" + decision + "]";
    }
}
