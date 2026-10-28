package zw.co.innbucks.middleware.kyc;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.kyc.store.KycEvidence;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Drafts expire with their files; rejected files go at the end of retention; approved evidence never does. */
class KycLifecycleJobTest {

    private final KycFixture f = new KycFixture();
    private final KycService service = f.service();
    private final KycDecisionApplier applier = f.applier();
    private final KycLifecycleJob job = f.job();

    @Test
    void aDraftPastItsDeadlineExpires_andItsFilesAreDeleted() {
        UUID lapsedOwner = f.customer("basic", null);
        UUID lapsed = f.open(service, lapsedOwner, "standard", KycFixture.ID_TYPED, null);
        f.upload(service, lapsedOwner, lapsed, KycFileKind.ID_FRONT);
        f.upload(service, lapsedOwner, lapsed, KycFileKind.SELFIE);
        f.clock.advance(Duration.ofDays(10));
        UUID freshOwner = f.customer("basic", null);
        UUID fresh = f.open(service, freshOwner, "standard", KycFixture.ID_TYPED, null);
        f.upload(service, freshOwner, fresh, KycFileKind.ID_FRONT);
        f.clock.advance(Duration.ofDays(5)); // lapsed is 15 days old, fresh 5

        job.run();

        assertThat(f.store.submissions.get(lapsed).status()).isEqualTo(KycSubmissionStatus.EXPIRED);
        assertThat(f.store.submissions.get(lapsed).expiresAt()).isNull();
        assertThat(f.store.files.values()).noneMatch(file -> file.submissionId().equals(lapsed));
        assertThat(f.store.evidence(lapsed, 1)).isEmpty();
        assertThat(f.store.submissions.get(fresh).status()).isEqualTo(KycSubmissionStatus.DRAFT);
        assertThat(f.store.evidence(fresh, 1)).hasSize(1);
        verify(f.audit).record(eq(AuditAction.KYC_SUBMISSION_EXPIRED), eq(AuditOutcome.SUCCESS), eq(lapsedOwner),
                isNull(), any());

        job.run(); // nothing left to do: idempotent
        verify(f.audit, times(1)).record(eq(AuditAction.KYC_SUBMISSION_EXPIRED), any(), any(), any(), any());
    }

    @Test
    void aReopenedRoundNotFinishedInTimeExpires_butTheCarriedFilesStayWithTheirRejectedRound() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        service.submit(customer, null, id);
        applier.reject(id, 1, List.of(KycRejectionReason.SELFIE_NOT_LIVE), Set.of(KycFileKind.SELFIE));
        f.upload(service, customer, id, KycFileKind.SELFIE); // a retake, never submitted
        f.clock.advance(Duration.ofDays(15));

        job.run();

        assertThat(f.store.submissions.get(id).status()).isEqualTo(KycSubmissionStatus.EXPIRED);
        assertThat(f.store.evidence(id, 2)).isEmpty();
        // round 1's three files remain until the rejected-file retention
        assertThat(f.store.evidence(id, 1)).hasSize(3);
        assertThat(f.store.files).hasSize(3);
    }

    @Test
    void rejectedFilesGoAtTheEndOfRetention_exceptThoseTheApprovedRoundStillShows() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        service.submit(customer, null, id);
        UUID rejectedFront = f.store.evidence(id, 1).stream()
                .filter(e -> e.kind() == KycFileKind.ID_FRONT).findFirst().orElseThrow().fileId();
        applier.reject(id, 1, List.of(KycRejectionReason.ID_PHOTO_UNREADABLE), Set.of(KycFileKind.ID_FRONT));
        f.upload(service, customer, id, KycFileKind.ID_FRONT);
        service.submit(customer, null, id);
        applier.approve(id, 2);
        List<UUID> approvedEvidence = f.store.evidence(id, 2).stream().map(KycEvidence::fileId).toList();

        f.clock.advance(Duration.ofDays(29));
        job.run();
        assertThat(f.store.files).containsKey(rejectedFront); // retention not over

        f.clock.advance(Duration.ofDays(2));
        job.run();

        assertThat(f.store.files).doesNotContainKey(rejectedFront);
        assertThat(f.store.files.keySet()).containsExactlyInAnyOrderElementsOf(approvedEvidence);
        assertThat(f.store.evidence(id, 1)).isEmpty();
        assertThat(f.store.findRound(id, 1).orElseThrow().filesPurgedAt()).isEqualTo(f.clock.instant());
        verify(f.audit).record(eq(AuditAction.KYC_FILES_PURGED), eq(AuditOutcome.SUCCESS), eq(customer), isNull(),
                any());

        f.clock.advance(Duration.ofDays(3650));
        job.run();
        assertThat(f.store.files.keySet()).containsExactlyInAnyOrderElementsOf(approvedEvidence);
    }

    @Test
    void oneItemFailingNeverStopsTheRest() {
        UUID a = f.customer("basic", null);
        UUID b = f.customer("basic", null);
        UUID first = f.open(service, a, "standard", KycFixture.ID_TYPED, null);
        UUID second = f.open(service, b, "standard", KycFixture.ID_TYPED, null);
        f.clock.advance(Duration.ofDays(15));
        f.store.failLocks.add(first);

        job.run();

        assertThat(f.store.submissions.get(first).status()).isEqualTo(KycSubmissionStatus.DRAFT);
        assertThat(f.store.submissions.get(second).status()).isEqualTo(KycSubmissionStatus.EXPIRED);
        assertThat(f.meters.counter("innbucks.kyc.lifecycle", "action", "expire", "outcome", "failed").count())
                .isEqualTo(1.0);
    }
}
