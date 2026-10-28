package zw.co.innbucks.middleware.kyc;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.kyc.KycDecisionApplier.Decision;
import zw.co.innbucks.middleware.kyc.KycDecisionApplier.KycDecisionException;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.store.KycEvidence;
import zw.co.innbucks.middleware.kyc.store.KycRoundRecord;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * Rounds: submitting freezes a round; a rejection with redo opens the next
 * one, into which ONLY the redo kinds are uploaded while every other kind is
 * carried forward by reference; a decision only ever lands on the round it
 * names. Driven through KycDecisionApplier, the review step's entry point.
 */
class KycRoundsTest {

    private final KycFixture f = new KycFixture();
    private final KycService service = f.service();
    private final KycDecisionApplier applier = f.applier();

    private UUID submittedStandard(UUID customer) {
        UUID id = f.completeStandard(service, customer);
        service.submit(customer, null, id);
        return id;
    }

    private Map<KycFileKind, KycEvidence> evidence(UUID id, int round) {
        return f.store.evidence(id, round).stream().collect(Collectors.toMap(KycEvidence::kind, Function.identity()));
    }

    @Test
    void aRejectionWithRedoOpensTheNextRound_carryingTheOtherFilesByReference() {
        UUID customer = f.customer("basic", null);
        UUID id = submittedStandard(customer);
        Map<KycFileKind, KycEvidence> round1 = evidence(id, 1);
        int filesBefore = f.store.files.size();
        f.clock.advance(Duration.ofHours(6));

        Decision decision = applier.reject(id, 1, List.of(KycRejectionReason.ID_PHOTO_UNREADABLE),
                Set.of(KycFileKind.ID_FRONT));

        assertThat(decision.reopenedRound()).isEqualTo(2);
        assertThat(decision.resubmitBy()).isEqualTo(f.clock.instant().plus(Duration.ofDays(14)));
        // round 1 keeps its decision and its evidence
        KycRoundRecord r1 = f.store.findRound(id, 1).orElseThrow();
        assertThat(r1.decision()).isEqualTo("rejected");
        assertThat(r1.redo()).containsExactly(KycFileKind.ID_FRONT);
        assertThat(evidence(id, 1)).isEqualTo(round1);
        // round 2 shows the SAME file rows for the carried kinds; no bytes copied
        Map<KycFileKind, KycEvidence> round2 = evidence(id, 2);
        assertThat(round2).containsOnlyKeys(KycFileKind.ID_BACK, KycFileKind.SELFIE);
        assertThat(round2.get(KycFileKind.ID_BACK).fileId()).isEqualTo(round1.get(KycFileKind.ID_BACK).fileId());
        assertThat(round2.get(KycFileKind.SELFIE).fileRound()).isEqualTo(1);
        assertThat(f.store.files).hasSize(filesBefore);
        // the ID is re-sealed to the new round's binding, same number
        KycRoundRecord r2 = f.store.findRound(id, 2).orElseThrow();
        assertThat(r2.frozen()).isFalse();
        assertThat(f.cipher.openText(r2.nationalIdCiphertext(), KycAad.nationalId(id, 2)))
                .isEqualTo(KycFixture.ID_CANONICAL);
        assertThat(r2.nationalIdHmac()).isEqualTo(r1.nationalIdHmac());

        KycStatusView.SubmissionView view = service.status(customer).submission();
        assertThat(view.status()).isEqualTo("rejected");
        assertThat(view.round()).isEqualTo(2);
        assertThat(view.rejection().reasons()).containsExactly("id_photo_unreadable");
        assertThat(view.rejection().redo()).containsExactly("id_front");
        assertThat(view.missing()).containsExactly("id_front");
        assertThat(view.expiresAt()).isNotNull();
        verify(f.audit).record(eq(AuditAction.KYC_ROUND_DECIDED), eq(AuditOutcome.SUCCESS), eq(customer),
                isNull(), any());
    }

    @Test
    void inTheReopenedRoundOnlyTheRedoKindsCanBeSent_andSubmittingFreezesWhatTheApproverSees() {
        UUID customer = f.customer("basic", null);
        UUID id = submittedStandard(customer);
        UUID oldFront = evidence(id, 1).get(KycFileKind.ID_FRONT).fileId();
        UUID back = evidence(id, 1).get(KycFileKind.ID_BACK).fileId();
        applier.reject(id, 1, List.of(KycRejectionReason.ID_PHOTO_UNREADABLE), Set.of(KycFileKind.ID_FRONT));

        assertThatThrownBy(() -> f.upload(service, customer, id, KycFileKind.ID_BACK))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_file_not_needed");
                    assertThat(ex.properties()).containsEntry("uploadable", List.of("id_front"));
                });
        assertThatThrownBy(() -> service.submit(customer, null, id))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.properties()).containsEntry("missing", List.of("id_front")));

        KycFileStoredView stored = f.upload(service, customer, id, KycFileKind.ID_FRONT);
        assertThat(stored.round()).isEqualTo(2);
        assertThat(stored.missing()).isEmpty();
        assertThat(service.submit(customer, null, id)).isEqualTo(new KycSubmitAccepted("pending_review", 2));

        Map<KycFileKind, KycEvidence> round2 = evidence(id, 2);
        assertThat(round2.get(KycFileKind.ID_FRONT).fileId()).isNotEqualTo(oldFront);
        assertThat(round2.get(KycFileKind.ID_FRONT).fileRound()).isEqualTo(2);
        assertThat(round2.get(KycFileKind.ID_BACK).fileId()).isEqualTo(back);
        assertThat(f.store.findRound(id, 2).orElseThrow().frozen()).isTrue();
        // round 1's rejected front is still there (until its retention), untouched by round 2
        assertThat(f.store.files).containsKey(oldFront);
        assertThat(evidence(id, 1).get(KycFileKind.ID_FRONT).fileId()).isEqualTo(oldFront);
    }

    @Test
    void aRejectionWithoutRedoClosesTheRequest_andANewOneCanBeOpened() {
        UUID customer = f.customer("basic", null);
        UUID id = submittedStandard(customer);

        Decision decision = applier.reject(id, 1, List.of(KycRejectionReason.NAME_MISMATCH), Set.of());

        assertThat(decision.reopenedRound()).isNull();
        assertThat(f.store.findOpen(customer)).isEmpty();
        KycStatusView.SubmissionView view = service.status(customer).submission();
        assertThat(view.status()).isEqualTo("rejected");
        assertThat(view.rejection().redo()).isEmpty();
        assertThat(view.missing()).isNull();
        assertThat(f.open(service, customer, "standard", "63-7654321 X 18", null)).isNotEqualTo(id);
    }

    @Test
    void approvingRecordsTheDecisionButChangesNoTier() {
        UUID customer = f.customer("basic", null);
        UUID id = submittedStandard(customer);

        applier.approve(id, 1);

        assertThat(f.store.submissions.get(id).status()).isEqualTo(KycSubmissionStatus.APPROVED);
        assertThat(f.store.findRound(id, 1).orElseThrow().decision()).isEqualTo("approved");
        assertThat(f.customers.get(customer).getKycTier()).isEqualTo("basic");
        assertThat(service.status(customer).kycTier()).isEqualTo("basic");
    }

    @Test
    void aDecisionLandsOnlyOnTheCurrentFrozenUndecidedRound() {
        UUID customer = f.customer("basic", null);
        UUID draft = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        assertThatThrownBy(() -> applier.approve(draft, 1)).isInstanceOf(KycDecisionException.class);

        UUID other = f.customer("basic", null);
        UUID id = submittedStandard(other);
        assertThatThrownBy(() -> applier.approve(id, 2)).isInstanceOf(KycDecisionException.class);
        applier.reject(id, 1, List.of(KycRejectionReason.ID_PHOTO_UNREADABLE), Set.of(KycFileKind.SELFIE));
        // round 1 is decided; round 2 is not frozen yet
        assertThatThrownBy(() -> applier.approve(id, 1)).isInstanceOf(KycDecisionException.class);
        assertThatThrownBy(() -> applier.approve(id, 2)).isInstanceOf(KycDecisionException.class);
        assertThatThrownBy(() -> applier.approve(UUID.randomUUID(), 1)).isInstanceOf(KycDecisionException.class);
    }

    @Test
    void aRejectionNeedsAReason_andCanOnlyAskForFilesTheTierHas() {
        UUID customer = f.customer("basic", null);
        UUID id = submittedStandard(customer);

        assertThatThrownBy(() -> applier.reject(id, 1, List.of(), Set.of(KycFileKind.SELFIE)))
                .isInstanceOf(KycDecisionException.class);
        assertThatThrownBy(() -> applier.reject(id, 1, List.of(KycRejectionReason.ADDRESS_UNREADABLE),
                Set.of(KycFileKind.PROOF_OF_ADDRESS)))
                .isInstanceOf(KycDecisionException.class).hasMessageContaining("proof_of_address");
        assertThat(f.store.findRound(id, 1).orElseThrow().decision()).isNull();
    }
}
