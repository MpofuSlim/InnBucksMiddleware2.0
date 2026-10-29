package zw.co.innbucks.middleware.kyc;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycStore.RoundRef;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Daily KYC housekeeping:
 *
 * <ol>
 *   <li><b>Expire</b> every unfinished round past its deadline
 *       ({@code innbucks.kyc.draft-ttl}, default 14 days, counted from the
 *       draft's creation or from the rejection that reopened it): the
 *       submission becomes {@code expired} and the files uploaded into that
 *       round are deleted.</li>
 *   <li><b>Purge</b> the files of REJECTED rounds once
 *       {@code innbucks.kyc.rejected-file-retention} has passed since the
 *       decision (default 30 days — a placeholder: retention is compliance's
 *       call). A file carried forward into a later round stays as long as
 *       that round references it.</li>
 * </ol>
 *
 * Approved rounds' files are never deleted: they are the evidence of record.
 *
 * <p><b>Why it runs without a lock:</b> the cell is a single replica, and the
 * job would be safe on several anyway — every item is handled in its own
 * transaction holding the submission row lock, re-checks its state under that
 * lock, and is idempotent (a second runner finds nothing left to do). The
 * customer's own requests take the same lock, so a sweep and an upload can
 * never interleave on one submission. A failing item is logged, counted and
 * skipped; it never stops the rest.
 *
 * <p>Deleting a row does not erase its bytes everywhere: they survive as dead
 * tuples until VACUUM and in every backup taken before, until those age out.
 */
@Slf4j
@Component
@ConditionalOnKycEnabled
public class KycLifecycleJob {

    static final int BATCH = 200;
    /** Upper bound on batches per run; with the seen-set, a stuck item can never spin the job. */
    static final int MAX_BATCHES = 100;

    private final KycStore store;
    private final KycHousekeeping housekeeping;
    private final AuditService audit;
    private final KycProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public KycLifecycleJob(KycStore store, KycHousekeeping housekeeping, AuditService audit,
                           KycProperties properties, PlatformTransactionManager transactionManager,
                           Clock clock, MeterRegistry meters) {
        this.store = store;
        this.housekeeping = housekeeping;
        this.audit = audit;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(cron = "${innbucks.kyc.lifecycle-cron:0 25 3 * * *}")
    public void run() {
        int expired = expireLapsed();
        int purged = purgeRejectedRounds();
        log.info("KYC lifecycle: {} submission(s) expired, {} rejected round(s) purged", expired, purged);
    }

    /** @return submissions expired */
    int expireLapsed() {
        Set<UUID> seen = new HashSet<>();
        int done = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Instant now = now();
            List<UUID> due = store.findLapsed(now, BATCH);
            int fresh = 0;
            for (UUID id : due) {
                if (!seen.add(id)) {
                    continue; // failed or already handled this run
                }
                fresh++;
                try {
                    Expiry expiry = transaction.execute(tx -> store.lockSubmission(id)
                            .filter(s -> s.lapsed(now))
                            .map(s -> new Expiry(s, housekeeping.expire(s, now)))
                            .orElse(null));
                    if (expiry != null) {
                        auditExpired(expiry);
                        done++;
                    }
                } catch (RuntimeException ex) {
                    count("expire", "failed");
                    log.error("KYC lifecycle: could not expire submission {}", id, ex);
                }
            }
            if (due.size() < BATCH || fresh == 0) {
                break;
            }
        }
        return done;
    }

    /** @return rejected rounds whose files were purged */
    int purgeRejectedRounds() {
        Set<RoundRef> seen = new HashSet<>();
        int done = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Instant now = now();
            Instant cutoff = now.minus(properties.rejectedFileRetention());
            List<RoundRef> due = store.findRejectedRoundsToPurge(cutoff, BATCH);
            int fresh = 0;
            for (RoundRef ref : due) {
                if (!seen.add(ref)) {
                    continue; // failed or already handled this run
                }
                fresh++;
                try {
                    Purge purge = transaction.execute(tx -> store.lockSubmission(ref.submissionId())
                            .map(s -> new Purge(s, ref.round(),
                                    housekeeping.purgeRejectedRound(ref.submissionId(), ref.round(), now)))
                            .orElse(null));
                    if (purge != null) {
                        auditPurged(purge);
                        done++;
                    }
                } catch (RuntimeException ex) {
                    count("purge", "failed");
                    log.error("KYC lifecycle: could not purge round {} of submission {}",
                            ref.round(), ref.submissionId(), ex);
                }
            }
            if (due.size() < BATCH || fresh == 0) {
                break;
            }
        }
        return done;
    }

    private record Expiry(KycSubmissionRecord submission, int filesDeleted) {
    }

    private record Purge(KycSubmissionRecord submission, int round, int filesDeleted) {
    }

    private void auditExpired(Expiry expiry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", expiry.submission().id().toString());
        metadata.put("round", expiry.submission().currentRound());
        metadata.put("filesDeleted", expiry.filesDeleted());
        metadata.put("trigger", "lifecycle_job");
        audit.record(AuditAction.KYC_SUBMISSION_EXPIRED, AuditOutcome.SUCCESS,
                expiry.submission().customerId(), null, metadata);
        count("expire", "done");
    }

    private void auditPurged(Purge purge) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", purge.submission().id().toString());
        metadata.put("round", purge.round());
        metadata.put("filesDeleted", purge.filesDeleted());
        metadata.put("retention", properties.rejectedFileRetention().toString());
        audit.record(AuditAction.KYC_FILES_PURGED, AuditOutcome.SUCCESS, purge.submission().customerId(), null,
                metadata);
        count("purge", "done");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void count(String action, String outcome) {
        meters.counter("innbucks.kyc.lifecycle", "action", action, "outcome", outcome).increment();
    }
}
