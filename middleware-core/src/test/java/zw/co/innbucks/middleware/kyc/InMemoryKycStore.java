package zw.co.innbucks.middleware.kyc;

import org.springframework.dao.DuplicateKeyException;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.image.ProcessedImage;
import zw.co.innbucks.middleware.kyc.store.KycEvidence;
import zw.co.innbucks.middleware.kyc.store.KycFileStore;
import zw.co.innbucks.middleware.kyc.store.KycRoundRecord;
import zw.co.innbucks.middleware.kyc.store.KycStore;
import zw.co.innbucks.middleware.kyc.store.KycSubmissionRecord;
import zw.co.innbucks.middleware.kyc.store.LatestFrozenRound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory KycStore + KycFileStore with the SAME semantics as the SQL
 * (one-open uniqueness, freeze/decide preconditions, evidence by reference,
 * unreferenced-file deletion), so the rounds logic can be unit-tested. The
 * SQL itself is proven against real Postgres by KycIntakeIntegrationTest.
 * Files are sealed with a real {@link KycCipher}, as the Postgres store does.
 */
public class InMemoryKycStore implements KycStore, KycFileStore {

    record RoundKey(UUID submissionId, int round) {
    }

    record EvidenceKey(UUID submissionId, int round, KycFileKind kind) {
    }

    record FileRow(UUID id, UUID submissionId, int round, KycFileKind kind, byte[] ciphertext,
                   int width, int height, int byteSize) {
    }

    final Map<UUID, KycSubmissionRecord> submissions = new LinkedHashMap<>();
    final Map<RoundKey, KycRoundRecord> rounds = new LinkedHashMap<>();
    final Map<EvidenceKey, UUID> evidence = new LinkedHashMap<>();
    final Map<UUID, FileRow> files = new LinkedHashMap<>();
    /** customer id -> sign-up national_id_hash (as RegisterService stored it). */
    final Map<UUID, String> signupHashes = new HashMap<>();
    /** Submissions whose row lock "fails" (a database error), to prove one bad item stops nothing else. */
    final Set<UUID> failLocks = new HashSet<>();

    private final KycCipher cipher;

    public InMemoryKycStore(KycCipher cipher) {
        this.cipher = cipher;
    }

    // --- KycStore -----------------------------------------------------------

    @Override
    public Optional<KycSubmissionRecord> findSubmission(UUID submissionId) {
        return Optional.ofNullable(submissions.get(submissionId));
    }

    @Override
    public Optional<KycSubmissionRecord> lockSubmission(UUID submissionId) {
        if (failLocks.contains(submissionId)) {
            throw new org.springframework.dao.CannotAcquireLockException("lock timeout (simulated)");
        }
        return findSubmission(submissionId);
    }

    @Override
    public Optional<KycSubmissionRecord> findOpen(UUID customerId) {
        return submissions.values().stream()
                .filter(s -> s.customerId().equals(customerId) && s.open())
                .findFirst();
    }

    @Override
    public Optional<KycSubmissionRecord> lockOpen(UUID customerId) {
        return findOpen(customerId);
    }

    @Override
    public Optional<KycSubmissionRecord> findLatest(UUID customerId) {
        return submissions.values().stream()
                .filter(s -> s.customerId().equals(customerId))
                .max(Comparator.comparing(KycSubmissionRecord::createdAt));
    }

    @Override
    public void insertSubmission(KycSubmissionRecord submission) {
        if (submission.open() && findOpen(submission.customerId()).isPresent()) {
            throw new DuplicateKeyException("uq_kyc_submission_one_open");
        }
        submissions.put(submission.id(), submission);
    }

    @Override
    public void updateSubmission(KycSubmissionRecord submission) {
        if (!submissions.containsKey(submission.id())) {
            throw new IllegalStateException("no submission " + submission.id());
        }
        submissions.put(submission.id(), submission);
    }

    @Override
    public void insertRound(KycRoundRecord round) {
        RoundKey key = new RoundKey(round.submissionId(), round.round());
        if (rounds.containsKey(key)) {
            throw new DuplicateKeyException("kyc_round pkey");
        }
        rounds.put(key, round);
    }

    @Override
    public Optional<KycRoundRecord> findRound(UUID submissionId, int round) {
        return Optional.ofNullable(rounds.get(new RoundKey(submissionId, round)));
    }

    @Override
    public Optional<KycRoundRecord> findLatestDecidedRound(UUID submissionId) {
        return rounds.values().stream()
                .filter(r -> r.submissionId().equals(submissionId) && r.decision() != null)
                .max(Comparator.comparingInt(KycRoundRecord::round));
    }

    @Override
    public Optional<LatestFrozenRound> findLatestFrozenRound(UUID customerId) {
        return rounds.values().stream()
                .filter(r -> r.frozen() && submissions.get(r.submissionId()).customerId().equals(customerId))
                .max(Comparator.comparing(KycRoundRecord::frozenAt).thenComparingInt(KycRoundRecord::round))
                .map(r -> {
                    Set<KycFileKind> kinds = EnumSet.noneOf(KycFileKind.class);
                    evidence(r.submissionId(), r.round()).forEach(e -> kinds.add(e.kind()));
                    return new LatestFrozenRound(r.maskedId(),
                            submissions.get(r.submissionId()).addressCiphertext() != null, kinds);
                });
    }

    @Override
    public void freezeRound(UUID submissionId, int round, Instant frozenAt, int duplicateCount) {
        KycRoundRecord r = findRound(submissionId, round).filter(x -> !x.frozen())
                .orElseThrow(() -> new IllegalStateException("no open round"));
        rounds.put(new RoundKey(submissionId, round), new KycRoundRecord(r.submissionId(), r.round(), r.openedAt(),
                frozenAt, r.nationalIdCiphertext(), r.nationalIdHmac(), r.maskedId(), r.signupMatch(),
                duplicateCount, r.decision(), r.decidedAt(), r.rejectionReasons(), r.redo(), r.filesPurgedAt()));
    }

    @Override
    public void recordDecision(UUID submissionId, int round, String decision, Instant decidedAt,
                               List<KycRejectionReason> reasons, Set<KycFileKind> redo) {
        KycRoundRecord r = findRound(submissionId, round).filter(x -> x.frozen() && x.decision() == null)
                .orElseThrow(() -> new IllegalStateException("no undecided frozen round"));
        rounds.put(new RoundKey(submissionId, round), new KycRoundRecord(r.submissionId(), r.round(), r.openedAt(),
                r.frozenAt(), r.nationalIdCiphertext(), r.nationalIdHmac(), r.maskedId(), r.signupMatch(),
                r.duplicateCount(), decision, decidedAt, List.copyOf(reasons),
                redo.isEmpty() ? EnumSet.noneOf(KycFileKind.class) : EnumSet.copyOf(redo), r.filesPurgedAt()));
    }

    @Override
    public List<KycEvidence> evidence(UUID submissionId, int round) {
        List<KycEvidence> out = new ArrayList<>();
        evidence.forEach((key, fileId) -> {
            if (key.submissionId().equals(submissionId) && key.round() == round) {
                out.add(new KycEvidence(key.kind(), fileId, files.get(fileId).round()));
            }
        });
        return out;
    }

    @Override
    public Optional<UUID> putEvidence(UUID submissionId, int round, KycFileKind kind, UUID fileId) {
        FileRow file = files.get(fileId);
        // the composite FK: evidence names a file of ITS submission and kind
        if (file == null || !file.submissionId().equals(submissionId) || file.kind() != kind) {
            throw new IllegalStateException("FK: no kyc_file " + fileId + " of " + submissionId + "/" + kind.code());
        }
        return Optional.ofNullable(evidence.put(new EvidenceKey(submissionId, round, kind), fileId));
    }

    @Override
    public void carryForward(UUID submissionId, int fromRound, int toRound, Set<KycFileKind> kinds) {
        for (KycFileKind kind : kinds) {
            UUID fileId = evidence.get(new EvidenceKey(submissionId, fromRound, kind));
            if (fileId != null) {
                evidence.put(new EvidenceKey(submissionId, toRound, kind), fileId);
            }
        }
    }

    @Override
    public int deleteEvidence(UUID submissionId, int round) {
        int before = evidence.size();
        evidence.keySet().removeIf(k -> k.submissionId().equals(submissionId) && k.round() == round);
        return before - evidence.size();
    }

    @Override
    public List<UUID> unreferencedFileIds(UUID submissionId) {
        Set<UUID> referenced = new HashSet<>(evidence.values());
        return files.values().stream()
                .filter(f -> f.submissionId().equals(submissionId) && !referenced.contains(f.id()))
                .map(FileRow::id)
                .toList();
    }

    @Override
    public int countOtherCustomersWithNationalId(UUID customerId, Collection<String> signupHashesToTry,
                                                 String canonicalHmac) {
        Set<UUID> others = new HashSet<>();
        signupHashes.forEach((customer, hash) -> {
            if (!customer.equals(customerId) && signupHashesToTry.contains(hash)) {
                others.add(customer);
            }
        });
        rounds.values().forEach(r -> {
            UUID owner = submissions.get(r.submissionId()).customerId();
            if (!owner.equals(customerId) && r.nationalIdHmac().equals(canonicalHmac)) {
                others.add(owner);
            }
        });
        return others.size();
    }

    @Override
    public List<UUID> findLapsed(Instant now, int limit) {
        return submissions.values().stream()
                .filter(s -> s.expiresAt() != null && !s.expiresAt().isAfter(now)
                        && (s.status() == KycSubmissionStatus.DRAFT || s.status() == KycSubmissionStatus.REJECTED))
                .limit(limit)
                .map(KycSubmissionRecord::id)
                .toList();
    }

    @Override
    public List<RoundRef> findRejectedRoundsToPurge(Instant decidedBefore, int limit) {
        return rounds.values().stream()
                .filter(r -> "rejected".equals(r.decision()) && !r.decidedAt().isAfter(decidedBefore)
                        && r.filesPurgedAt() == null)
                .limit(limit)
                .map(r -> new RoundRef(r.submissionId(), r.round()))
                .toList();
    }

    @Override
    public void markFilesPurged(UUID submissionId, int round, Instant at) {
        KycRoundRecord r = findRound(submissionId, round).orElseThrow();
        rounds.put(new RoundKey(submissionId, round), new KycRoundRecord(r.submissionId(), r.round(), r.openedAt(),
                r.frozenAt(), r.nationalIdCiphertext(), r.nationalIdHmac(), r.maskedId(), r.signupMatch(),
                r.duplicateCount(), r.decision(), r.decidedAt(), r.rejectionReasons(), r.redo(), at));
    }

    // --- KycFileStore -------------------------------------------------------

    @Override
    public StoredKycFile store(UUID fileId, UUID submissionId, int round, KycFileKind kind, ProcessedImage image,
                               Instant at) {
        if (!rounds.containsKey(new RoundKey(submissionId, round))) {
            throw new IllegalStateException("FK: no kyc_round " + submissionId + "/" + round);
        }
        files.put(fileId, new FileRow(fileId, submissionId, round, kind,
                cipher.seal(image.jpeg(), KycAad.file(submissionId, round, kind.code(), fileId)),
                image.width(), image.height(), image.byteSize()));
        return new StoredKycFile(fileId, kind, round, image.width(), image.height(), image.byteSize());
    }

    @Override
    public byte[] read(UUID submissionId, KycEvidence evidence) {
        return cipher.open(files.get(evidence.fileId()).ciphertext(),
                KycAad.file(submissionId, evidence.fileRound(), evidence.kind().code(), evidence.fileId()));
    }

    @Override
    public int delete(Collection<UUID> fileIds) {
        if (evidence.values().stream().anyMatch(fileIds::contains)) {
            throw new IllegalStateException("FK: a kyc_round_evidence row still references a deleted file");
        }
        int deleted = 0;
        for (UUID id : fileIds) {
            if (files.remove(id) != null) {
                deleted++;
            }
        }
        return deleted;
    }
}
