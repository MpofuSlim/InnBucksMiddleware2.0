package zw.co.innbucks.middleware.kyc;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.kyc.store.KycFileStore;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two ways files leave the store, shared by the write paths (which expire
 * a lapsed submission inline, so a customer is never stuck behind one until
 * the nightly sweep) and {@link KycLifecycleJob}.
 *
 * <p>Both run INSIDE the caller's transaction, holding the submission row
 * lock. Both delete only files no round's evidence references any more —
 * which is what makes approved evidence untouchable: an approved round's
 * evidence rows are never deleted, and a file carried forward into it stays
 * referenced by it even after the rejected round it came from is purged.
 */
@Component
@ConditionalOnKycEnabled
public class KycHousekeeping {

    private final KycStore store;
    private final KycFileStore files;

    public KycHousekeeping(KycStore store, KycFileStore files) {
        this.store = store;
        this.files = files;
    }

    /**
     * Ends an unfinished round that passed its deadline: drops the round's
     * evidence, deletes the files uploaded into it, marks the submission
     * expired. Files carried forward into it stay with the (rejected) round
     * they came from and follow its retention.
     *
     * @return files deleted
     */
    public int expire(KycSubmissionRecord locked, Instant now) {
        store.deleteEvidence(locked.id(), locked.currentRound());
        int deleted = deleteUnreferenced(locked.id());
        store.updateSubmission(locked.withState(KycSubmissionStatus.EXPIRED, locked.currentRound(),
                locked.submittedAt(), locked.decidedAt(), null, now));
        return deleted;
    }

    /**
     * Ends the retention of a REJECTED round: drops its evidence and deletes
     * every file that leaves unreferenced.
     *
     * @return files deleted
     */
    public int purgeRejectedRound(UUID submissionId, int round, Instant now) {
        store.deleteEvidence(submissionId, round);
        int deleted = deleteUnreferenced(submissionId);
        store.markFilesPurged(submissionId, round, now);
        return deleted;
    }

    private int deleteUnreferenced(UUID submissionId) {
        List<UUID> orphans = store.unreferencedFileIds(submissionId);
        return orphans.isEmpty() ? 0 : files.delete(orphans);
    }
}
