package zw.co.innbucks.middleware.kyc;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.middleware.audit.AuditAction;
import zw.co.innbucks.middleware.audit.AuditOutcome;
import zw.co.innbucks.middleware.audit.AuditService;
import zw.co.innbucks.middleware.common.country.CountryProperties;
import zw.co.innbucks.middleware.customer.Customer;
import zw.co.innbucks.middleware.customer.CustomerRepository;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.customer.KycTierResolution;
import zw.co.innbucks.middleware.customer.KycTierResolver;
import zw.co.innbucks.middleware.customer.KycTierUnavailableException;
import zw.co.innbucks.middleware.customer.NationalIdHasher;
import zw.co.innbucks.middleware.idempotency.IdempotencyKeys;
import zw.co.innbucks.middleware.idempotency.IdempotencyService;
import zw.co.innbucks.middleware.kyc.KycStatusView.NextTier;
import zw.co.innbucks.middleware.kyc.KycStatusView.OnFile;
import zw.co.innbucks.middleware.kyc.KycStatusView.Rejection;
import zw.co.innbucks.middleware.kyc.KycStatusView.SubmissionView;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.image.KycImageProcessor;
import zw.co.innbucks.middleware.kyc.image.KycImageRejectedException;
import zw.co.innbucks.middleware.kyc.image.KycUploadBusyException;
import zw.co.innbucks.middleware.kyc.image.ProcessedImage;
import zw.co.innbucks.middleware.kyc.nationalid.KycNationalIds;
import zw.co.innbucks.middleware.kyc.store.EvidenceOnRecord;
import zw.co.innbucks.middleware.kyc.store.KycEvidence;
import zw.co.innbucks.middleware.kyc.store.KycFileStore;
import zw.co.innbucks.middleware.kyc.store.KycFileStore.StoredKycFile;
import zw.co.innbucks.middleware.kyc.store.KycRoundRecord;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;
import zw.co.innbucks.middleware.me.ProfileNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * KYC intake for the app: open a request, upload its images into the open
 * round, submit (which freezes the round), and read where things stand.
 * Nothing here DECIDES — approval, rejection and the tier itself belong to
 * the review step ({@link KycDecisionApplier} is its in-process entry point).
 *
 * <p>Rules every path keeps:
 * <ul>
 *   <li>The tier is read once per request through {@link KycTierResolver};
 *       a tier that could not be read is 503 {@code kyc_tier_unavailable},
 *       never a guessed "you can upgrade".</li>
 *   <li>Rate limits (per customer and per address) come first, before
 *       anything is read or decoded.</li>
 *   <li>Another customer's submission id is the same 404 as an unknown one.</li>
 *   <li>Duplicates of the ID are counted for the reviewer at submit time and
 *       NEVER refused or revealed: a refusal would tell anyone whether a
 *       number belongs to an account.</li>
 *   <li>Image work (CPU) happens before the write transaction; the
 *       transaction re-checks the round under the submission row lock, so a
 *       file can never land in a round that was frozen meanwhile.</li>
 *   <li>Nothing logged or audited carries an ID number, an address or image
 *       bytes.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnKycEnabled
public class KycService {

    static final String CREATE_PATH = "/me/kyc/submissions";

    /**
     * Domain separation for the address in the idempotency fingerprint: a
     * canonical ID is [A-Z0-9] only, so nothing it hashes can collide with
     * this prefix.
     */
    private static final String ADDRESS_FINGERPRINT_PREFIX = "kyc-address:";

    private final KycStore store;
    private final KycFileStore fileStore;
    private final KycCipher cipher;
    private final KycNationalIds nationalIds;
    private final NationalIdHasher hasher;
    private final KycImageProcessor images;
    private final KycHousekeeping housekeeping;
    private final KycRateLimiter rateLimiter;
    private final CustomerRepository customers;
    private final KycTierResolver tierResolver;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final KycProperties properties;
    private final ZoneId zone;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public KycService(KycStore store,
                      KycFileStore fileStore,
                      KycCipher cipher,
                      KycNationalIds nationalIds,
                      NationalIdHasher hasher,
                      KycImageProcessor images,
                      KycHousekeeping housekeeping,
                      KycRateLimiter rateLimiter,
                      CustomerRepository customers,
                      KycTierResolver tierResolver,
                      IdempotencyService idempotency,
                      AuditService audit,
                      KycProperties properties,
                      CountryProperties countryProperties,
                      PlatformTransactionManager transactionManager,
                      Clock clock,
                      MeterRegistry meters) {
        this.store = store;
        this.fileStore = fileStore;
        this.cipher = cipher;
        this.nationalIds = nationalIds;
        this.hasher = hasher;
        this.images = images;
        this.housekeeping = housekeeping;
        this.rateLimiter = rateLimiter;
        this.customers = customers;
        this.tierResolver = tierResolver;
        this.idempotency = idempotency;
        this.audit = audit;
        this.properties = properties;
        this.zone = countryProperties.country().zoneId();
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.meters = meters;
    }

    // --- GET /me/kyc ----------------------------------------------------------

    public KycStatusView status(UUID customerId) {
        Customer customer = requireCustomer(customerId);
        KycTier tier = authoritativeTier(customer);
        Instant now = clock.instant();

        KycTier next = tier.next();
        NextTier nextTier = next == null ? null : new NextTier(next.dbValue(), KycRequirements.needs(next));
        // The evidence of RECORD (latest approved round), never what is merely
        // pending or was rejected: that is `submission`'s to describe, and a
        // rejected round's files vanish at the end of their retention.
        OnFile onFile = store.findEvidenceOnRecord(customerId).map(KycService::onFile).orElse(OnFile.NOTHING);
        SubmissionView submission = store.findOpen(customerId)
                .or(() -> store.findLatest(customerId))
                .map(s -> view(s, now))
                .orElse(null);
        return new KycStatusView(tier.dbValue(), nextTier, onFile, submission);
    }

    private static OnFile onFile(EvidenceOnRecord round) {
        Set<KycFileKind> kinds = round.kinds();
        return new OnFile(round.maskedId(),
                kinds.contains(KycFileKind.ID_FRONT),
                kinds.contains(KycFileKind.ID_BACK),
                kinds.contains(KycFileKind.SELFIE),
                kinds.contains(KycFileKind.PROOF_OF_ADDRESS),
                round.hasAddress());
    }

    private SubmissionView view(KycSubmissionRecord s, Instant now) {
        boolean lapsed = s.lapsed(now);
        KycSubmissionStatus shown = lapsed ? KycSubmissionStatus.EXPIRED : s.status();
        boolean customerCanChange = s.acceptingFiles() && !lapsed;

        Rejection rejection = null;
        if (shown == KycSubmissionStatus.REJECTED) {
            rejection = store.findLatestDecidedRound(s.id())
                    .map(r -> new Rejection(
                            r.rejectionReasons().stream().map(KycRejectionReason::code).toList(),
                            KycRequirements.codes(r.redo())))
                    .orElse(null);
        }
        List<String> missing = null;
        if (customerCanChange) {
            missing = KycRequirements.codes(missing(s, store.evidence(s.id(), s.currentRound())));
        }
        // Every round of a submission carries the same number; its mask is all that ever leaves.
        String maskedId = store.findRound(s.id(), s.currentRound()).map(KycRoundRecord::maskedId).orElse(null);
        return new SubmissionView(s.id().toString(), s.targetTier().dbValue(), maskedId, shown.dbValue(),
                s.currentRound(), market(s.submittedAt()), market(s.decidedAt()), rejection, missing,
                customerCanChange ? market(s.expiresAt()) : null);
    }

    // --- POST /me/kyc/submissions ---------------------------------------------

    /**
     * Opens a request. The ID is canonicalised, validated and HMAC'd BEFORE
     * the idempotency layer sees anything, and the body handed to it carries
     * that HMAC (and the address's) — never the number: its fingerprint is
     * stored in {@code idempotency_record} for a day, and an unkeyed hash of
     * an ID number is a brute-force away from the number.
     */
    public IdempotencyService.Result<KycSubmissionCreated> create(UUID customerId, String clientIp,
                                                                  String rawIdempotencyKey,
                                                                  CreateKycSubmissionRequest request) {
        rateLimiter.create(customerId, clientIp);
        Customer customer = requireCustomer(customerId);
        KycTier current = authoritativeTier(customer);
        if (current.next() == null) {
            throw KycRequestException.notAvailable();
        }
        KycTier target = target(request.targetTier(), current);
        KycNationalIds.Canonical nationalId = nationalIds.canonicalise(request.nationalId());
        String address = address(target, request.address());

        CreateFingerprint fingerprint = new CreateFingerprint(target.dbValue(), nationalId.hmac(),
                address == null ? null : hasher.hash(ADDRESS_FINGERPRINT_PREFIX + address));
        String key = IdempotencyKeys.namespaced("kyc-submission:" + customerId, rawIdempotencyKey);
        return idempotency.execute(key, "POST", CREATE_PATH, fingerprint, KycSubmissionCreated.class, 201,
                () -> open(customer, target, nationalId, address));
    }

    /** What the idempotency layer fingerprints for a create: hashes only. */
    record CreateFingerprint(String targetTier, String nationalIdHmac, String addressHmac) {
    }

    private KycSubmissionCreated open(Customer customer, KycTier target, KycNationalIds.Canonical nationalId,
                                      String address) {
        UUID customerId = customer.getId();
        Instant now = now();
        SignupMatch signupMatch = nationalIds.signupMatch(customer.getNationalIdHash(), nationalId.value());
        UUID submissionId = UUID.randomUUID();

        OpenOutcome outcome;
        try {
            outcome = transaction.execute(tx -> {
                Optional<KycSubmissionRecord> open = store.lockOpen(customerId);
                Expired expired = null;
                if (open.isPresent()) {
                    KycSubmissionRecord existing = open.get();
                    if (!existing.lapsed(now)) {
                        return new OpenOutcome(existing.id(), null);
                    }
                    expired = new Expired(existing, housekeeping.expire(existing, now));
                }
                store.insertSubmission(new KycSubmissionRecord(submissionId, customerId, target,
                        KycSubmissionStatus.DRAFT, 1,
                        address == null ? null : cipher.sealText(address, KycAad.address(submissionId)),
                        now, null, null, now.plus(properties.draftTtl()), now));
                store.insertRound(new KycRoundRecord(submissionId, 1, now, null,
                        cipher.sealText(nationalId.value(), KycAad.nationalId(submissionId, 1)),
                        nationalId.hmac(), nationalId.masked(), signupMatch, null, null, null,
                        List.of(), Set.of(), null));
                return new OpenOutcome(null, expired);
            });
        } catch (DuplicateKeyException race) {
            // A concurrent create committed first: the partial unique index
            // is the arbiter. Point the app at the winner.
            UUID winner = store.findOpen(customerId).map(KycSubmissionRecord::id).orElseThrow(() -> race);
            throw KycRequestException.submissionOpen(winner);
        }
        if (outcome.conflictWith() != null) {
            throw KycRequestException.submissionOpen(outcome.conflictWith());
        }
        if (outcome.expired() != null) {
            auditExpired(outcome.expired(), "superseded_by_new_request");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", submissionId.toString());
        metadata.put("targetTier", target.dbValue());
        metadata.put("round", 1);
        metadata.put("signupMatch", signupMatch.dbValue());
        audit.record(AuditAction.KYC_SUBMISSION_OPENED, AuditOutcome.SUCCESS, customerId, null, metadata);
        count("opened");
        log.info("KYC submission {} opened for customer {} (target {})", submissionId, customerId, target.dbValue());
        return new KycSubmissionCreated(submissionId.toString(), 1,
                KycRequirements.codes(KycRequirements.fileKinds(target)));
    }

    private record OpenOutcome(UUID conflictWith, Expired expired) {
    }

    private record Expired(KycSubmissionRecord submission, int filesDeleted) {
    }

    private KycTier target(String raw, KycTier current) {
        KycTier target = KycTier.parse(raw).orElseThrow(() ->
                KycRequestException.targetInvalid("targetTier must be standard or enhanced."));
        if (target.ordinal() <= current.ordinal()) {
            throw KycRequestException.targetInvalid("Ask for a level above your current one ("
                    + current.dbValue() + ").");
        }
        return target;
    }

    private String address(KycTier target, String raw) {
        boolean given = raw != null && !raw.isBlank();
        if (!KycRequirements.requiresAddress(target)) {
            if (given) {
                throw KycRequestException.addressInvalid("An address is only collected for the enhanced level.");
            }
            return null;
        }
        if (!given) {
            throw KycRequestException.addressInvalid("Enter your residential address.");
        }
        String address = raw.strip();
        if (address.length() > properties.addressMaxLength()) {
            throw KycRequestException.addressInvalid("The address is too long (at most "
                    + properties.addressMaxLength() + " characters).");
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'
                    || Character.getType(c) == Character.FORMAT) {
                throw KycRequestException.addressInvalid("The address contains characters we can't store.");
            }
        }
        return address;
    }

    // --- PUT|POST /me/kyc/submissions/{id}/files/{kind} -------------------------

    /**
     * Stores one image in the OPEN round, replacing an earlier upload of the
     * same kind in that round. Caps come before reading: a declared
     * {@code Content-Length} over the byte cap is refused without touching the
     * body, and an undeclared (chunked) body is read only up to the cap.
     */
    public KycFileStoredView upload(UUID customerId, String clientIp, UUID submissionId, String kindCode,
                                    long declaredLength, InputStream body) {
        rateLimiter.upload(customerId, clientIp);
        KycFileKind kind = KycFileKind.fromCode(kindCode).orElseThrow(KycRequestException::fileKindInvalid);
        int maxBytes = properties.image().maxBytes();
        if (declaredLength > maxBytes) {
            count("file_too_large");
            throw KycRequestException.fileTooLarge(maxBytes);
        }
        KycSubmissionRecord submission = owned(store.findSubmission(submissionId), customerId);
        requireAcceptingFiles(submission, clock.instant());
        Set<KycFileKind> uploadable = uploadable(submission, store.evidence(submissionId, submission.currentRound()));
        if (!uploadable.contains(kind)) {
            throw KycRequestException.fileNotNeeded(kind, uploadable);
        }

        ProcessedImage image;
        // The admission comes BEFORE the body is read and is held until the
        // image is processed: it is what bounds how many upload bodies sit in
        // the heap at once (the decode semaphore inside it only bounds decodes,
        // and a request waiting for one would otherwise hold its body).
        try (KycImageProcessor.Admission admitted = images.admit()) {
            byte[] upload = readBounded(body, maxBytes, declaredLength);
            image = images.process(upload, kind);
        } catch (KycImageRejectedException rejected) {
            count("file_rejected_" + rejected.reason().code());
            throw rejected;
        } catch (KycUploadBusyException busy) {
            count("file_busy");
            throw busy;
        }
        int round = submission.currentRound();

        StoreOutcome stored = transaction.execute(tx -> {
            Instant at = now();
            KycSubmissionRecord locked = owned(store.lockSubmission(submissionId), customerId);
            if (!locked.acceptingFiles() || locked.lapsed(at) || locked.currentRound() != round) {
                throw KycRequestException.notOpen(locked.lapsed(at) ? KycSubmissionStatus.EXPIRED : locked.status());
            }
            List<KycEvidence> evidence = store.evidence(submissionId, round);
            Set<KycFileKind> stillUploadable = uploadable(locked, evidence);
            if (!stillUploadable.contains(kind)) {
                throw KycRequestException.fileNotNeeded(kind, stillUploadable);
            }
            UUID fileId = UUID.randomUUID();
            StoredKycFile file = fileStore.store(fileId, submissionId, round, kind, image, at);
            Optional<UUID> previous = store.putEvidence(submissionId, round, kind, fileId);
            // Only this round's own upload is ever replaced (a carried-forward
            // kind is not uploadable), and nothing else references it.
            previous.ifPresent(old -> fileStore.delete(List.of(old)));
            Set<KycFileKind> have = EnumSet.of(kind);
            evidence.forEach(e -> have.add(e.kind()));
            Set<KycFileKind> missing = EnumSet.copyOf(KycRequirements.fileKinds(locked.targetTier()));
            missing.removeAll(have);
            return new StoreOutcome(file, previous.isPresent(), missing);
        });

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", submissionId.toString());
        metadata.put("round", round);
        metadata.put("kind", kind.code());
        metadata.put("byteSize", stored.file().byteSize());
        metadata.put("width", stored.file().width());
        metadata.put("height", stored.file().height());
        metadata.put("replaced", stored.replaced());
        audit.record(AuditAction.KYC_FILE_STORED, AuditOutcome.SUCCESS, customerId, null, metadata);
        count("file_stored");
        return new KycFileStoredView(kind.code(), round, stored.file().width(), stored.file().height(),
                stored.file().byteSize(), stored.replaced(), KycRequirements.codes(stored.missing()));
    }

    private record StoreOutcome(StoredKycFile file, boolean replaced, Set<KycFileKind> missing) {
    }

    /**
     * Reads the body into ONE array. A declared length (already checked
     * against the cap) is read exactly — the container ends the stream there —
     * so the body costs its own size and no copy. An undeclared (chunked) body
     * is read only up to the cap plus one byte: one more is too large.
     */
    private byte[] readBounded(InputStream body, int maxBytes, long declaredLength) {
        try {
            if (declaredLength >= 0) {
                byte[] exact = new byte[(int) declaredLength];
                if (body.readNBytes(exact, 0, exact.length) < exact.length) {
                    throw cutShort();
                }
                return exact;
            }
            byte[] read = body.readNBytes(maxBytes + 1);
            if (read.length > maxBytes) {
                count("file_too_large");
                throw KycRequestException.fileTooLarge(maxBytes);
            }
            return read;
        } catch (IOException ex) {
            throw cutShort();
        }
    }

    private static KycImageRejectedException cutShort() {
        return new KycImageRejectedException(KycImageRejectedException.Reason.CORRUPT, "the upload was cut short");
    }

    // --- POST /me/kyc/submissions/{id}/submit ---------------------------------

    /**
     * Freezes the open round and hands it to staff. Idempotent: submitting a
     * request that is already pending answers the same 202. The duplicate
     * count is taken HERE, so the reviewer sees the number as of the evidence
     * they decide.
     */
    public KycSubmitAccepted submit(UUID customerId, String clientIp, UUID submissionId) {
        rateLimiter.submit(customerId, clientIp);
        SubmitOutcome outcome = transaction.execute(tx -> {
            Instant now = now();
            KycSubmissionRecord s = owned(store.lockSubmission(submissionId), customerId);
            if (s.status() == KycSubmissionStatus.PENDING_REVIEW) {
                return new SubmitOutcome(s.currentRound(), null, null, null);
            }
            if (s.lapsed(now)) {
                return new SubmitOutcome(s.currentRound(), null, null, new Expired(s, housekeeping.expire(s, now)));
            }
            if (!s.acceptingFiles()) {
                throw KycRequestException.notOpen(s.status());
            }
            List<KycEvidence> evidence = store.evidence(s.id(), s.currentRound());
            Set<KycFileKind> missing = missing(s, evidence);
            if (!missing.isEmpty()) {
                throw KycRequestException.incomplete(KycRequirements.codes(missing));
            }
            KycRoundRecord round = store.findRound(s.id(), s.currentRound()).orElseThrow(() ->
                    new IllegalStateException("kyc_submission " + s.id() + " has no row for its current round"));
            String canonical = cipher.openText(round.nationalIdCiphertext(),
                    KycAad.nationalId(s.id(), round.round()));
            int duplicates = store.countOtherCustomersWithNationalId(customerId,
                    nationalIds.spellingHashes(canonical), round.nationalIdHmac());
            store.freezeRound(s.id(), round.round(), now, duplicates);
            store.updateSubmission(s.withState(KycSubmissionStatus.PENDING_REVIEW, round.round(), now, null, null, now));
            return new SubmitOutcome(round.round(), round, duplicates, null);
        });

        if (outcome.expired() != null) {
            auditExpired(outcome.expired(), "deadline_passed");
            throw KycRequestException.notOpen(KycSubmissionStatus.EXPIRED);
        }
        if (outcome.frozen() != null) {
            KycRoundRecord round = outcome.frozen();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("submissionId", submissionId.toString());
            metadata.put("round", round.round());
            metadata.put("signupMatch", round.signupMatch().dbValue());
            metadata.put("duplicateCount", outcome.duplicates());
            audit.record(AuditAction.KYC_ROUND_SUBMITTED, AuditOutcome.SUCCESS, customerId, null, metadata);
            count("submitted");
            if (outcome.duplicates() > 0) {
                count("submitted_with_duplicates");
            }
        }
        return new KycSubmitAccepted(KycSubmissionStatus.PENDING_REVIEW.dbValue(), outcome.round());
    }

    private record SubmitOutcome(int round, KycRoundRecord frozen, Integer duplicates, Expired expired) {
    }

    // --- shared ---------------------------------------------------------------

    /**
     * Kinds the customer may upload into the current round: everything the
     * target tier needs, minus what the round CARRIES FORWARD from an earlier
     * one (after a rejection only the redo kinds are open).
     */
    static Set<KycFileKind> uploadable(KycSubmissionRecord s, List<KycEvidence> evidence) {
        Set<KycFileKind> out = EnumSet.copyOf(KycRequirements.fileKinds(s.targetTier()));
        for (KycEvidence e : evidence) {
            if (e.fileRound() < s.currentRound()) {
                out.remove(e.kind());
            }
        }
        return out;
    }

    static Set<KycFileKind> missing(KycSubmissionRecord s, List<KycEvidence> evidence) {
        Set<KycFileKind> out = EnumSet.copyOf(KycRequirements.fileKinds(s.targetTier()));
        evidence.forEach(e -> out.remove(e.kind()));
        return out;
    }

    private void requireAcceptingFiles(KycSubmissionRecord s, Instant now) {
        if (s.lapsed(now)) {
            throw KycRequestException.notOpen(KycSubmissionStatus.EXPIRED);
        }
        if (!s.acceptingFiles()) {
            throw KycRequestException.notOpen(s.status());
        }
    }

    private static KycSubmissionRecord owned(Optional<KycSubmissionRecord> found, UUID customerId) {
        return found.filter(s -> s.customerId().equals(customerId))
                .orElseThrow(KycRequestException::submissionNotFound);
    }

    private Customer requireCustomer(UUID customerId) {
        Customer customer = customers.findById(customerId).orElseThrow(() -> new ProfileNotFoundException(customerId));
        if (customer.getCoreExternalId() == null) {
            throw new ProfileNotFoundException(customerId);
        }
        return customer;
    }

    /** Offering or refusing an upgrade must never rest on a tier that could not be read. */
    private KycTier authoritativeTier(Customer customer) {
        KycTierResolution resolution = tierResolver.resolve(customer);
        if (!resolution.authoritative()) {
            throw new KycTierUnavailableException();
        }
        return resolution.tier();
    }

    private void auditExpired(Expired expired, String why) {
        KycSubmissionRecord s = expired.submission();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submissionId", s.id().toString());
        metadata.put("round", s.currentRound());
        metadata.put("filesDeleted", expired.filesDeleted());
        metadata.put("trigger", why);
        audit.record(AuditAction.KYC_SUBMISSION_EXPIRED, AuditOutcome.SUCCESS, s.customerId(), null, metadata);
        count("expired");
    }

    private String market(Instant instant) {
        return instant == null ? null
                : instant.truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /** Microsecond precision: what Postgres keeps, so a value read back compares equal. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private void count(String event) {
        meters.counter("innbucks.kyc.events", "event", event).increment();
    }
}
