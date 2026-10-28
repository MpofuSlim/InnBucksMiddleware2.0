package zw.co.innbucks.middleware.kyc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.store.KycRoundRecord;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a reviewer's decision to ONE round. In-process only: no endpoint
 * calls it in this change. The review step (a later change: Fineract-side
 * review and approval, the live tier read, the staff read surface) decides,
 * and calls this to record the outcome on the middleware's side.
 *
 * <p><b>Rounds are what keep a decision honest.</b> A decision names the
 * round it is about, and only the CURRENT, frozen, undecided round of a
 * {@code pending_review} submission can be decided — a decision made on what
 * the reviewer saw can never land on evidence the customer changed since.
 * A rejection with a redo list opens round N+1: the customer re-uploads ONLY
 * the redo kinds, and every other kind is carried forward BY REFERENCE to
 * round N's file (the same {@code kyc_file} row, no bytes copied), so the
 * next approver sees exactly the evidence of the round they decide.
 *
 * <p>Approving changes NO tier. The tier a payment is judged by comes from
 * {@code KycTierResolver}; moving it is the review step's job.
 *
 * <p>Audit is best-effort here, like every audit today. Whether a decision
 * must be REFUSED when its audit row cannot be written is for the review
 * step to settle with a required-write variant of the audit service.
 */
@Slf4j
@Service
@ConditionalOnKycEnabled
public class KycDecisionApplier {

    static final String APPROVED = "approved";
    static final String REJECTED = "rejected";

    private final KycStore store;
    private final KycCipher cipher;
    private final AuditService audit;
    private final KycProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public KycDecisionApplier(KycStore store, KycCipher cipher, AuditService audit, KycProperties properties,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What a decision did. {@code reopenedRound} is set when a rejection opened the next round. */
    public record Decision(UUID submissionId, int decidedRound, KycSubmissionStatus status,
                           Integer reopenedRound, Instant resubmitBy) {
    }

    public Decision approve(UUID submissionId, int round) {
        Decision decision = transaction.execute(tx -> {
            Instant now = now();
            KycSubmissionRecord s = awaitingDecision(submissionId, round);
            store.recordDecision(submissionId, round, APPROVED, now, List.of(), Set.of());
            store.updateSubmission(s.withState(KycSubmissionStatus.APPROVED, round, s.submittedAt(), now, null, now));
            return new Decision(submissionId, round, KycSubmissionStatus.APPROVED, null, null);
        });
        auditDecision(decision, APPROVED, List.of(), Set.of());
        return decision;
    }

    /**
     * @param reasons at least one
     * @param redo    the kinds the customer must retake, all needed by the
     *                target tier; empty closes the submission for good (a
     *                wrong ID number or a wrong address cannot be fixed by
     *                retaking a photo — the customer opens a new request)
     */
    public Decision reject(UUID submissionId, int round, List<KycRejectionReason> reasons, Set<KycFileKind> redo) {
        if (reasons == null || reasons.isEmpty()) {
            throw new KycDecisionException("a rejection needs at least one reason");
        }
        Set<KycFileKind> redoKinds = redo == null || redo.isEmpty()
                ? EnumSet.noneOf(KycFileKind.class) : EnumSet.copyOf(redo);
        Decision decision = transaction.execute(tx -> {
            Instant now = now();
            KycSubmissionRecord s = awaitingDecision(submissionId, round);
            Set<KycFileKind> needed = KycRequirements.fileKinds(s.targetTier());
            if (!needed.containsAll(redoKinds)) {
                throw new KycDecisionException("redo " + KycRequirements.codes(redoKinds)
                        + " names a file a " + s.targetTier().dbValue() + " request does not have");
            }
            store.recordDecision(submissionId, round, REJECTED, now, reasons, redoKinds);
            if (redoKinds.isEmpty()) {
                store.updateSubmission(s.withState(KycSubmissionStatus.REJECTED, round, s.submittedAt(), now,
                        null, now));
                return new Decision(submissionId, round, KycSubmissionStatus.REJECTED, null, null);
            }
            KycRoundRecord decided = store.findRound(submissionId, round).orElseThrow();
            int next = round + 1;
            // The ID is bound to its round: re-seal it for the new one rather
            // than copy a ciphertext that would not open there.
            String canonical = cipher.openText(decided.nationalIdCiphertext(), KycAad.nationalId(submissionId, round));
            store.insertRound(new KycRoundRecord(submissionId, next, now, null,
                    cipher.sealText(canonical, KycAad.nationalId(submissionId, next)),
                    decided.nationalIdHmac(), decided.maskedId(), decided.signupMatch(),
                    null, null, null, List.of(), Set.of(), null));
            Set<KycFileKind> carried = EnumSet.copyOf(needed);
            carried.removeAll(redoKinds);
            store.carryForward(submissionId, round, next, carried);
            Instant resubmitBy = now.plus(properties.draftTtl());
            store.updateSubmission(s.withState(KycSubmissionStatus.REJECTED, next, s.submittedAt(), now,
                    resubmitBy, now));
            return new Decision(submissionId, round, KycSubmissionStatus.REJECTED, next, resubmitBy);
        });
        auditDecision(decision, REJECTED, reasons, redoKinds);
        return decision;
    }

    private KycSubmissionRecord awaitingDecision(UUID submissionId, int round) {
        KycSubmissionRecord s = store.lockSubmission(submissionId)
                .orElseThrow(() -> new KycDecisionException("no submission " + submissionId));
        if (s.status() != KycSubmissionStatus.PENDING_REVIEW || s.currentRound() != round) {
            throw new KycDecisionException("submission " + submissionId + " round " + round
                    + " is not awaiting a decision (status " + s.status().dbValue() + ", round "
                    + s.currentRound() + ")");
        }
        KycRoundRecord r = store.findRound(submissionId, round)
                .orElseThrow(() -> new KycDecisionException("no round " + round + " on " + submissionId));
        if (!r.frozen() || r.decision() != null) {
            throw new KycDecisionException("round " + round + " of " + submissionId
                    + " is not frozen and undecided");
        }
        return s;
    }

    private void auditDecision(Decision decision, String outcome, List<KycRejectionReason> reasons,
                               Set<KycFileKind> redo) {
        KycSubmissionRecord s = store.findSubmission(decision.submissionId()).orElseThrow();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", decision.submissionId().toString());
        metadata.put("round", decision.decidedRound());
        metadata.put("decision", outcome);
        metadata.put("reasons", reasons.stream().map(KycRejectionReason::code).toList());
        metadata.put("redo", KycRequirements.codes(redo));
        if (decision.reopenedRound() != null) {
            metadata.put("reopenedRound", decision.reopenedRound());
        }
        audit.record(AuditAction.KYC_ROUND_DECIDED, AuditOutcome.SUCCESS, s.customerId(), null, metadata);
        log.info("KYC submission {} round {} {}", decision.submissionId(), decision.decidedRound(), outcome);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A decision that does not fit the submission's state. A programming or race error, never a 4xx. */
    public static class KycDecisionException extends IllegalStateException {
        public KycDecisionException(String message) {
            super("KYC decision refused: " + message);
        }
    }
}
