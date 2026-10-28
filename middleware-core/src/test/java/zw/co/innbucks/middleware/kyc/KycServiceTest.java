package zw.co.innbucks.middleware.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.image.KycImageProcessor;
import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException;
import zw.co.innbucks.middleware.kyc.image.KycUploadBusyException;
import zw.co.innbucks.middleware.kyc.image.TestImages;
import zw.co.innbucks.middleware.kyc.nationalid.InvalidNationalIdException;
import zw.co.innbucks.middleware.kyc.store.KycRoundRecord;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;
import zw.co.innbucks.middleware.ratelimit.RateLimitExceededException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Intake rules end to end through KycService over the in-memory store: open,
 * upload into the open round, submit (freeze), and the status the app reads.
 */
class KycServiceTest {

    private final KycFixture f = new KycFixture();
    private final KycService service = f.service();

    // --- create -------------------------------------------------------------

    @Test
    void openingAStandardRequest_storesRound1SealedAndListsTheFilesItNeeds() {
        UUID customer = f.customer("basic", null);

        KycSubmissionCreated created = service.create(customer, "198.51.100.7", "key-1",
                new CreateKycSubmissionRequest("standard", KycFixture.ID_TYPED, null)).body();

        assertThat(created.round()).isEqualTo(1);
        assertThat(created.needs()).containsExactly("id_front", "id_back", "selfie");
        UUID id = UUID.fromString(created.id());
        KycSubmissionRecord s = f.store.submissions.get(id);
        assertThat(s.status()).isEqualTo(KycSubmissionStatus.DRAFT);
        assertThat(s.expiresAt()).isEqualTo(KycFixture.T0.plus(Duration.ofDays(14)));
        assertThat(s.addressCiphertext()).isNull();

        KycRoundRecord round = f.store.findRound(id, 1).orElseThrow();
        assertThat(round.frozen()).isFalse();
        assertThat(round.nationalIdHmac()).isEqualTo(f.hasher.hash(KycFixture.ID_CANONICAL));
        assertThat(round.maskedId()).isEqualTo("63" + "•".repeat(7) + "X18");
        assertThat(round.signupMatch()).isEqualTo(SignupMatch.NONE_GIVEN);
        assertThat(round.duplicateCount()).isNull();
        assertThat(round.nationalIdCiphertext()).startsWith("v1:").doesNotContain("1234567");
        assertThat(f.cipher.openText(round.nationalIdCiphertext(), KycAad.nationalId(id, 1)))
                .isEqualTo(KycFixture.ID_CANONICAL);
    }

    @Test
    void theSubmissionOpenedAuditCarriesNoIdNumber() {
        UUID customer = f.customer("basic", "63-1234567 x 18");
        f.open(service, customer, "standard", KycFixture.ID_TYPED, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(f.audit).record(eq(AuditAction.KYC_SUBMISSION_OPENED), eq(AuditOutcome.SUCCESS), eq(customer),
                isNull(), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("signupMatch", "match").containsEntry("round", 1);
        assertThat(metadata.getValue().toString()).doesNotContain("1234567");
    }

    @Test
    void theIdempotencyFingerprintCarriesHmacsNeverTheNumberOrTheAddress() throws Exception {
        UUID customer = f.customer("basic", null);
        service.create(customer, "198.51.100.7", "key-2",
                new CreateKycSubmissionRequest("enhanced", KycFixture.ID_TYPED, KycFixture.ADDRESS));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(f.idempotency).execute(key.capture(), eq("POST"), eq("/me/kyc/submissions"), body.capture(),
                eq(KycSubmissionCreated.class), eq(201), any());
        // what IdempotencyService fingerprints: it serialises this with Jackson
        String json = new ObjectMapper().writeValueAsString(body.getValue());
        assertThat(json).doesNotContain("1234567").doesNotContain("Example Road")
                .contains(f.hasher.hash(KycFixture.ID_CANONICAL));
        assertThat(key.getValue()).hasSize(64).doesNotContain("key-2");
    }

    @Test
    void enhancedNeedsAnAddress_whichIsSealedToItsSubmission_andStandardRefusesOne() {
        UUID customer = f.customer("basic", null);
        assertThatThrownBy(() -> f.open(service, customer, "enhanced", KycFixture.ID_TYPED, " "))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_address_invalid"));
        assertThatThrownBy(() -> f.open(service, customer, "standard", KycFixture.ID_TYPED, KycFixture.ADDRESS))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_address_invalid"));
        assertThatThrownBy(() -> f.open(service, customer, "enhanced", KycFixture.ID_TYPED, "x".repeat(501)))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.getMessage()).contains("too long"));

        UUID id = f.open(service, customer, "enhanced", KycFixture.ID_TYPED, "  " + KycFixture.ADDRESS + " ");

        String sealed = f.store.submissions.get(id).addressCiphertext();
        assertThat(sealed).startsWith("v1:").doesNotContain("Example");
        assertThat(f.cipher.openText(sealed, KycAad.address(id))).isEqualTo(KycFixture.ADDRESS);
        assertThat(service.status(customer).submission().missing())
                .containsExactly("id_front", "id_back", "selfie", "proof_of_address");
    }

    @Test
    void theTargetMustBeATierAboveTheCurrentOne() {
        UUID basic = f.customer("basic", null);
        UUID standard = f.customer("standard", null);
        UUID enhanced = f.customer("enhanced", null);

        for (String target : new String[]{"basic", "premium", "", null}) {
            assertThatThrownBy(() -> f.open(service, basic, target, KycFixture.ID_TYPED, null))
                    .isInstanceOfSatisfying(KycRequestException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo("kyc_target_invalid"));
        }
        assertThatThrownBy(() -> f.open(service, standard, "standard", KycFixture.ID_TYPED, null))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_target_invalid"));
        // At the top nothing is available, whatever the body says.
        assertThatThrownBy(() -> f.open(service, enhanced, "premium", "garbage", null))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_not_available");
                    assertThat(ex.status().value()).isEqualTo(409);
                });
        // basic -> enhanced directly is allowed
        assertThat(f.open(service, basic, "enhanced", KycFixture.ID_TYPED, KycFixture.ADDRESS)).isNotNull();
    }

    @Test
    void aMalformedIdIsRefusedAndNothingIsStored() {
        UUID customer = f.customer("basic", null);
        assertThatThrownBy(() -> f.open(service, customer, "standard", "63-12345 X 18", null))
                .isInstanceOf(InvalidNationalIdException.class);
        assertThat(f.store.submissions).isEmpty();
        verify(f.idempotency, never()).execute(anyString(), anyString(), anyString(), any(), any(), anyInt(), any());
    }

    @Test
    void aSecondRequestWhileOneIsOpenIs409WithTheOpenOnesId() {
        UUID customer = f.customer("basic", null);
        UUID first = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);

        assertThatThrownBy(() -> f.open(service, customer, "enhanced", KycFixture.ID_TYPED, KycFixture.ADDRESS))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_submission_open");
                    assertThat(ex.properties()).containsEntry("submissionId", first.toString());
                });

        // still 409 once it is with staff
        f.upload(service, customer, first, KycFileKind.ID_FRONT);
        f.upload(service, customer, first, KycFileKind.ID_BACK);
        f.upload(service, customer, first, KycFileKind.SELFIE);
        service.submit(customer, "198.51.100.7", first);
        assertThatThrownBy(() -> f.open(service, customer, "standard", KycFixture.ID_TYPED, null))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_submission_open"));
    }

    @Test
    void aLapsedDraftIsExpiredOnTheSpot_soTheCustomerIsNeverStuckBehindIt() {
        UUID customer = f.customer("basic", null);
        UUID stale = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        f.upload(service, customer, stale, KycFileKind.ID_FRONT);
        f.clock.advance(Duration.ofDays(15));

        UUID fresh = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);

        assertThat(fresh).isNotEqualTo(stale);
        assertThat(f.store.submissions.get(stale).status()).isEqualTo(KycSubmissionStatus.EXPIRED);
        assertThat(f.store.files.values()).noneMatch(file -> file.submissionId().equals(stale));
        verify(f.audit).record(eq(AuditAction.KYC_SUBMISSION_EXPIRED), eq(AuditOutcome.SUCCESS), eq(customer),
                isNull(), any());
    }

    @Test
    void theTierThatCannotBeReadIsNeverAGuessedUpgradeOffer() {
        f.tierResolver = c -> KycTierResolution.unavailable();
        KycService guarded = f.service();
        UUID customer = f.customer("basic", null);

        assertThatThrownBy(() -> guarded.status(customer)).isInstanceOf(KycTierUnavailableException.class);
        assertThatThrownBy(() -> f.open(guarded, customer, "standard", KycFixture.ID_TYPED, null))
                .isInstanceOf(KycTierUnavailableException.class);
    }

    @Test
    void anUnregisteredCustomerIsNotFound() {
        assertThatThrownBy(() -> service.status(UUID.randomUUID())).isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void rateLimitsComeFirst() {
        UUID customer = f.customer("basic", null);
        doThrow(new RateLimitExceededException(30)).when(f.rateLimiter).create(customer, "198.51.100.7");

        assertThatThrownBy(() -> f.open(service, customer, "standard", "not even validated", null))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(f.store.submissions).isEmpty();
    }

    // --- upload ---------------------------------------------------------------

    @Test
    void anUploadIsStoredSealed_andTheAnswerListsWhatIsStillMissing() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);

        KycFileStoredView stored = f.upload(service, customer, id, KycFileKind.ID_FRONT);

        assertThat(stored.kind()).isEqualTo("id_front");
        assertThat(stored.round()).isEqualTo(1);
        assertThat(stored.width()).isEqualTo(800);
        assertThat(stored.replaced()).isFalse();
        assertThat(stored.missing()).containsExactly("id_back", "selfie");
        InMemoryKycStore.FileRow row = f.store.files.values().iterator().next();
        assertThat(new String(row.ciphertext(), 0, 3, StandardCharsets.US_ASCII)).isEqualTo("v1:");
        assertThat(f.store.read(id, f.store.evidence(id, 1).get(0))[0] & 0xFF).isEqualTo(0xFF); // opens to a JPEG
        verify(f.audit).record(eq(AuditAction.KYC_FILE_STORED), eq(AuditOutcome.SUCCESS), eq(customer), isNull(),
                any());
    }

    @Test
    void sendingAKindAgainInTheOpenRoundReplacesIt() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        f.upload(service, customer, id, KycFileKind.SELFIE);
        UUID first = f.store.evidence(id, 1).get(0).fileId();

        KycFileStoredView again = f.upload(service, customer, id, KycFileKind.SELFIE);

        assertThat(again.replaced()).isTrue();
        assertThat(f.store.files).hasSize(1).doesNotContainKey(first);
        assertThat(f.store.evidence(id, 1)).singleElement()
                .satisfies(e -> assertThat(e.fileId()).isNotEqualTo(first));
    }

    @Test
    void anotherCustomersRequestIsTheSame404AsAnUnknownOne() {
        UUID owner = f.customer("basic", null);
        UUID stranger = f.customer("basic", null);
        UUID id = f.open(service, owner, "standard", KycFixture.ID_TYPED, null);

        for (UUID target : new UUID[]{id, UUID.randomUUID()}) {
            assertThatThrownBy(() -> f.upload(service, stranger, target, KycFileKind.ID_FRONT))
                    .isInstanceOfSatisfying(KycRequestException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo("kyc_submission_not_found"));
            assertThatThrownBy(() -> service.submit(stranger, "198.51.100.7", target))
                    .isInstanceOfSatisfying(KycRequestException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo("kyc_submission_not_found"));
        }
    }

    @Test
    void anUnknownKindAndAKindTheTierDoesNotNeedAre400() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);

        assertThatThrownBy(() -> service.upload(customer, null, id, "passport", 10, InputStream.nullInputStream()))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_file_kind_invalid"));
        assertThatThrownBy(() -> f.upload(service, customer, id, KycFileKind.PROOF_OF_ADDRESS))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_file_not_needed");
                    assertThat(ex.status().value()).isEqualTo(400);
                });
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutReadingTheBody() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        InputStream mustNotBeRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the body was read");
            }
        };

        assertThatThrownBy(() -> service.upload(customer, null, id, "id_front", 2 * 1024 * 1024 + 1, mustNotBeRead))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_file_too_large");
                    assertThat(ex.status().value()).isEqualTo(413);
                    assertThat(ex.properties()).containsEntry("maxBytes", 2 * 1024 * 1024);
                });
    }

    @Test
    void anUndeclaredBodyIsReadOnlyUpToTheCap() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        byte[] big = new byte[2 * 1024 * 1024 + 10];

        assertThatThrownBy(() -> service.upload(customer, null, id, "id_front", -1, new ByteArrayInputStream(big)))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("kyc_file_too_large"));
    }

    @Test
    void whenEveryUploadPlaceIsTakenTheNextIsTurnedAwayBeforeItsBodyIsRead() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        InputStream mustNotBeRead = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the body was read");
            }
        };
        List<KycImageProcessor.Admission> held = new ArrayList<>();
        for (int i = 0; i < f.properties.image().maxConcurrentUploads(); i++) {
            held.add(f.images.admit());
        }

        assertThatThrownBy(() -> service.upload(customer, null, id, "id_front", 300_000, mustNotBeRead))
                .isInstanceOfSatisfying(KycUploadBusyException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isPositive());
        assertThat(f.meters.counter("innbucks.kyc.events", "event", "file_busy").count()).isEqualTo(1.0);

        held.forEach(KycImageProcessor.Admission::close);
        assertThat(f.upload(service, customer, id, KycFileKind.ID_FRONT).kind()).isEqualTo("id_front");
    }

    @Test
    void everyUploadGivesItsPlaceBack_storedRefusedOrCutShort() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        byte[] jpeg = TestImages.jpeg(800, 600);
        byte[] png = TestImages.png(400, 300);
        int places = f.properties.image().maxConcurrentUploads();

        // More attempts than there are places: a leak would turn the last into a 503.
        for (int i = 0; i < places + 1; i++) {
            f.upload(service, customer, id, KycFileKind.ID_FRONT);
            assertThatThrownBy(() -> service.upload(customer, null, id, "id_back", png.length,
                    new ByteArrayInputStream(png))).isInstanceOf(KycImageRejectedException.class);
            // declares more than it sends: the stream ends early
            assertThatThrownBy(() -> service.upload(customer, null, id, "id_back", jpeg.length + 10,
                    new ByteArrayInputStream(jpeg))).isInstanceOfSatisfying(KycImageRejectedException.class,
                    ex -> assertThat(ex.reason()).isEqualTo(KycImageRejectedException.Reason.CORRUPT));
        }
        assertThat(f.upload(service, customer, id, KycFileKind.ID_BACK).kind()).isEqualTo("id_back");
    }

    @Test
    void aNonJpegIsRefusedAndNothingIsStored() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        byte[] png = TestImages.png(400, 300);

        assertThatThrownBy(() -> service.upload(customer, null, id, "id_front", png.length,
                new ByteArrayInputStream(png))).isInstanceOf(KycImageRejectedException.class);
        assertThat(f.store.files).isEmpty();
    }

    @Test
    void noFileLandsInARoundOnceItIsSubmitted() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        service.submit(customer, null, id);
        int before = f.store.files.size();

        assertThatThrownBy(() -> f.upload(service, customer, id, KycFileKind.SELFIE))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_submission_not_open");
                    assertThat(ex.properties()).containsEntry("submissionStatus", "pending_review");
                });
        assertThat(f.store.files).hasSize(before);
    }

    @Test
    void anUploadToALapsedDraftIsRefusedAsExpired() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        f.clock.advance(Duration.ofDays(14));

        assertThatThrownBy(() -> f.upload(service, customer, id, KycFileKind.ID_FRONT))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.properties()).containsEntry("submissionStatus", "expired"));
    }

    // --- submit ---------------------------------------------------------------

    @Test
    void submittingWithFilesMissingIs422ListingThem() {
        UUID customer = f.customer("basic", null);
        UUID id = f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        f.upload(service, customer, id, KycFileKind.ID_BACK);

        assertThatThrownBy(() -> service.submit(customer, null, id))
                .isInstanceOfSatisfying(KycRequestException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo("kyc_incomplete");
                    assertThat(ex.properties()).containsEntry("missing", java.util.List.of("id_front", "selfie"));
                });
        assertThat(f.store.findRound(id, 1).orElseThrow().frozen()).isFalse();
    }

    @Test
    void submittingFreezesTheRound_andCountsDuplicatesForTheReviewerOnly() {
        // Two OTHER customers hold this number: one typed it differently at
        // sign-up, one put it on a KYC request of their own.
        f.customer("basic", "63 1234567 x 18");
        UUID otherKyc = f.customer("basic", null);
        f.open(service, otherKyc, "standard", "631234567X18", null);
        UUID unrelated = f.customer("basic", "63-7654321 X 18");
        UUID customer = f.customer("basic", KycFixture.ID_TYPED);
        UUID id = f.completeStandard(service, customer);
        f.clock.advance(Duration.ofMinutes(3));

        KycSubmitAccepted accepted = service.submit(customer, "198.51.100.7", id);

        assertThat(accepted.status()).isEqualTo("pending_review");
        assertThat(accepted.round()).isEqualTo(1);
        KycRoundRecord round = f.store.findRound(id, 1).orElseThrow();
        assertThat(round.frozenAt()).isEqualTo(f.clock.instant());
        assertThat(round.duplicateCount()).isEqualTo(2);
        assertThat(round.signupMatch()).isEqualTo(SignupMatch.MATCH);
        KycSubmissionRecord s = f.store.submissions.get(id);
        assertThat(s.status()).isEqualTo(KycSubmissionStatus.PENDING_REVIEW);
        assertThat(s.expiresAt()).isNull();
        assertThat(s.submittedAt()).isEqualTo(f.clock.instant());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(f.audit).record(eq(AuditAction.KYC_ROUND_SUBMITTED), eq(AuditOutcome.SUCCESS), eq(customer),
                isNull(), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("duplicateCount", 2).containsEntry("round", 1);
        assertThat(unrelated).isNotNull();
    }

    @Test
    void submittingTwiceAnswersTheSame202() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        service.submit(customer, null, id);

        assertThat(service.submit(customer, null, id)).isEqualTo(new KycSubmitAccepted("pending_review", 1));
    }

    @Test
    void submittingALapsedDraftExpiresIt() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        f.clock.advance(Duration.ofDays(20));

        assertThatThrownBy(() -> service.submit(customer, null, id))
                .isInstanceOfSatisfying(KycRequestException.class,
                        ex -> assertThat(ex.properties()).containsEntry("submissionStatus", "expired"));
        assertThat(f.store.submissions.get(id).status()).isEqualTo(KycSubmissionStatus.EXPIRED);
        assertThat(f.store.files).isEmpty();
    }

    // --- status -------------------------------------------------------------

    @Test
    void aCustomerWhoNeverAppliedSeesTheNextTierAndNothingOnFile() {
        KycStatusView view = service.status(f.customer("basic", null));

        assertThat(view.kycTier()).isEqualTo("basic");
        assertThat(view.nextTier().tier()).isEqualTo("standard");
        assertThat(view.nextTier().needs()).containsExactly("national_id", "id_front", "id_back", "selfie");
        assertThat(view.onFile()).isEqualTo(KycStatusView.OnFile.NOTHING);
        assertThat(view.submission()).isNull();
    }

    @Test
    void standardIsOfferedEnhanced_andEnhancedNothing() {
        assertThat(service.status(f.customer("standard", null)).nextTier().needs())
                .containsExactly("national_id", "id_front", "id_back", "selfie", "address", "proof_of_address");
        assertThat(service.status(f.customer("enhanced", null)).nextTier()).isNull();
    }

    @Test
    void aDraftShowsWhatIsMissingAndItsDeadline_atTheMarketOffset() {
        UUID customer = f.customer("basic", null);
        f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        UUID id = f.store.findOpen(customer).orElseThrow().id();
        f.upload(service, customer, id, KycFileKind.ID_FRONT);

        KycStatusView.SubmissionView s = service.status(customer).submission();

        assertThat(s.status()).isEqualTo("draft");
        assertThat(s.missing()).containsExactly("id_back", "selfie");
        assertThat(s.expiresAt()).isEqualTo("2026-11-11T09:40:00+02:00");
        assertThat(s.submittedAt()).isNull();
        assertThat(s.rejection()).isNull();
    }

    @Test
    void onFileDescribesWhatWasLastSentForReview_withTheMaskedIdOnly() {
        UUID customer = f.customer("basic", null);
        UUID id = f.completeStandard(service, customer);
        assertThat(service.status(customer).onFile().nationalId()).isNull(); // nothing SENT yet
        service.submit(customer, null, id);

        KycStatusView view = service.status(customer);

        assertThat(view.onFile()).isEqualTo(new KycStatusView.OnFile(
                "63" + "•".repeat(7) + "X18", true, true, true, false, false));
        assertThat(view.submission().status()).isEqualTo("pending_review");
        assertThat(view.submission().missing()).isNull();
        assertThat(view.submission().submittedAt()).isEqualTo("2026-10-28T09:40:00+02:00");
    }

    @Test
    void aLapsedDraftReadsAsExpiredBeforeTheSweepGetsToIt() {
        UUID customer = f.customer("basic", null);
        f.open(service, customer, "standard", KycFixture.ID_TYPED, null);
        f.clock.advance(Duration.ofDays(15));

        KycStatusView.SubmissionView s = service.status(customer).submission();

        assertThat(s.status()).isEqualTo("expired");
        assertThat(s.missing()).isNull();
        assertThat(s.expiresAt()).isNull();
    }
}
