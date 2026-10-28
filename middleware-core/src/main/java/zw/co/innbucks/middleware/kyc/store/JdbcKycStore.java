package zw.co.innbucks.middleware.kyc.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.middleware.customer.KycTier;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.KycRejectionReason;
import zw.co.innbucks.middleware.kyc.KycSubmissionStatus;
import zw.co.innbucks.middleware.kyc.SignupMatch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Postgres implementation of {@link KycStore}. Every INSERT goes through
 * {@link JdbcTemplate} (client-assigned ids: Spring Data JDBC's {@code save()}
 * would emit an UPDATE that touches no row).
 */
@Repository
@ConditionalOnKycEnabled
public class JdbcKycStore implements KycStore {

    /** The predicate of the partial unique index uq_kyc_submission_one_open — keep them identical. */
    public static final String OPEN_PREDICATE =
            "(status IN ('draft', 'pending_review') OR (status = 'rejected' AND expires_at IS NOT NULL))";

    private static final String SUBMISSION_COLUMNS = """
            id, customer_id, target_tier, status, current_round, address_ciphertext,
            created_at, submitted_at, decided_at, expires_at, updated_at
            """;

    private static final String ROUND_COLUMNS = """
            submission_id, round, opened_at, frozen_at, national_id_ciphertext, national_id_hmac,
            masked_id, signup_match, duplicate_count, decision, decided_at, rejection_reasons, redo,
            files_purged_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcKycStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<KycSubmissionRecord> findSubmission(UUID submissionId) {
        return one(jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM kyc_submission WHERE id = ?",
                SUBMISSION, submissionId));
    }

    @Override
    public Optional<KycSubmissionRecord> lockSubmission(UUID submissionId) {
        return one(jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM kyc_submission WHERE id = ? FOR UPDATE",
                SUBMISSION, submissionId));
    }

    @Override
    public Optional<KycSubmissionRecord> findOpen(UUID customerId) {
        return one(jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM kyc_submission WHERE customer_id = ? AND "
                + OPEN_PREDICATE, SUBMISSION, customerId));
    }

    @Override
    public Optional<KycSubmissionRecord> lockOpen(UUID customerId) {
        return one(jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM kyc_submission WHERE customer_id = ? AND "
                + OPEN_PREDICATE + " FOR UPDATE", SUBMISSION, customerId));
    }

    @Override
    public Optional<KycSubmissionRecord> findLatest(UUID customerId) {
        return one(jdbc.query("SELECT " + SUBMISSION_COLUMNS + " FROM kyc_submission WHERE customer_id = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT 1", SUBMISSION, customerId));
    }

    @Override
    public void insertSubmission(KycSubmissionRecord s) {
        jdbc.update("""
                INSERT INTO kyc_submission
                    (id, customer_id, target_tier, status, current_round, address_ciphertext,
                     created_at, submitted_at, decided_at, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                s.id(), s.customerId(), s.targetTier().dbValue(), s.status().dbValue(), s.currentRound(),
                s.addressCiphertext(), ts(s.createdAt()), ts(s.submittedAt()), ts(s.decidedAt()),
                ts(s.expiresAt()), ts(s.updatedAt()));
    }

    @Override
    public void updateSubmission(KycSubmissionRecord s) {
        int updated = jdbc.update("""
                UPDATE kyc_submission
                   SET status = ?, current_round = ?, submitted_at = ?, decided_at = ?, expires_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """,
                s.status().dbValue(), s.currentRound(), ts(s.submittedAt()), ts(s.decidedAt()),
                ts(s.expiresAt()), ts(s.updatedAt()), s.id());
        requireOne(updated, "kyc_submission " + s.id());
    }

    @Override
    public void insertRound(KycRoundRecord r) {
        jdbc.update("""
                INSERT INTO kyc_round
                    (submission_id, round, opened_at, frozen_at, national_id_ciphertext, national_id_hmac,
                     masked_id, signup_match, duplicate_count, decision, decided_at, rejection_reasons, redo,
                     files_purged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.submissionId(), r.round(), ts(r.openedAt()), ts(r.frozenAt()), r.nationalIdCiphertext(),
                r.nationalIdHmac(), r.maskedId(), r.signupMatch().dbValue(), r.duplicateCount(), r.decision(),
                ts(r.decidedAt()), joinReasons(r.rejectionReasons()), joinKinds(r.redo()), ts(r.filesPurgedAt()));
    }

    @Override
    public Optional<KycRoundRecord> findRound(UUID submissionId, int round) {
        return one(jdbc.query("SELECT " + ROUND_COLUMNS + " FROM kyc_round WHERE submission_id = ? AND round = ?",
                ROUND, submissionId, round));
    }

    @Override
    public Optional<KycRoundRecord> findLatestDecidedRound(UUID submissionId) {
        return one(jdbc.query("SELECT " + ROUND_COLUMNS + " FROM kyc_round WHERE submission_id = ? "
                + "AND decision IS NOT NULL ORDER BY round DESC LIMIT 1", ROUND, submissionId));
    }

    @Override
    public Optional<LatestFrozenRound> findLatestFrozenRound(UUID customerId) {
        List<Object[]> rows = jdbc.query("""
                SELECT r.submission_id, r.round, r.masked_id, s.address_ciphertext IS NOT NULL AS has_address
                  FROM kyc_round r
                  JOIN kyc_submission s ON s.id = r.submission_id
                 WHERE s.customer_id = ? AND r.frozen_at IS NOT NULL
                 ORDER BY r.frozen_at DESC, r.round DESC
                 LIMIT 1
                """, (rs, n) -> new Object[]{
                rs.getObject("submission_id", UUID.class), rs.getInt("round"),
                rs.getString("masked_id"), rs.getBoolean("has_address")}, customerId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        Set<KycFileKind> kinds = evidence((UUID) row[0], (Integer) row[1]).stream()
                .map(KycEvidence::kind)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(KycFileKind.class)));
        return Optional.of(new LatestFrozenRound((String) row[2], (Boolean) row[3], kinds));
    }

    @Override
    public void freezeRound(UUID submissionId, int round, Instant frozenAt, int duplicateCount) {
        int updated = jdbc.update("""
                UPDATE kyc_round SET frozen_at = ?, duplicate_count = ?
                 WHERE submission_id = ? AND round = ? AND frozen_at IS NULL
                """, ts(frozenAt), duplicateCount, submissionId, round);
        requireOne(updated, "open kyc_round " + submissionId + "/" + round);
    }

    @Override
    public void recordDecision(UUID submissionId, int round, String decision, Instant decidedAt,
                               List<KycRejectionReason> reasons, Set<KycFileKind> redo) {
        int updated = jdbc.update("""
                UPDATE kyc_round SET decision = ?, decided_at = ?, rejection_reasons = ?, redo = ?
                 WHERE submission_id = ? AND round = ? AND frozen_at IS NOT NULL AND decision IS NULL
                """, decision, ts(decidedAt), joinReasons(reasons), joinKinds(redo), submissionId, round);
        requireOne(updated, "undecided frozen kyc_round " + submissionId + "/" + round);
    }

    @Override
    public List<KycEvidence> evidence(UUID submissionId, int round) {
        return jdbc.query("""
                SELECT e.kind, e.file_id, f.round AS file_round
                  FROM kyc_round_evidence e
                  JOIN kyc_file f ON f.id = e.file_id
                 WHERE e.submission_id = ? AND e.round = ?
                """, (rs, n) -> new KycEvidence(
                KycFileKind.fromCode(rs.getString("kind")).orElseThrow(),
                rs.getObject("file_id", UUID.class),
                rs.getInt("file_round")), submissionId, round);
    }

    @Override
    public Optional<UUID> putEvidence(UUID submissionId, int round, KycFileKind kind, UUID fileId) {
        Optional<UUID> previous = one(jdbc.query("""
                SELECT file_id FROM kyc_round_evidence WHERE submission_id = ? AND round = ? AND kind = ?
                """, (rs, n) -> rs.getObject("file_id", UUID.class), submissionId, round, kind.code()));
        jdbc.update("""
                INSERT INTO kyc_round_evidence (submission_id, round, kind, file_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (submission_id, round, kind) DO UPDATE SET file_id = EXCLUDED.file_id
                """, submissionId, round, kind.code(), fileId);
        return previous;
    }

    @Override
    public void carryForward(UUID submissionId, int fromRound, int toRound, Set<KycFileKind> kinds) {
        if (kinds.isEmpty()) {
            return;
        }
        List<String> codes = kinds.stream().map(KycFileKind::code).toList();
        String placeholders = String.join(", ", codes.stream().map(c -> "?").toList());
        List<Object> args = new ArrayList<>(List.of(toRound, submissionId, fromRound));
        args.addAll(codes);
        jdbc.update("""
                INSERT INTO kyc_round_evidence (submission_id, round, kind, file_id)
                SELECT submission_id, ?, kind, file_id
                  FROM kyc_round_evidence
                 WHERE submission_id = ? AND round = ? AND kind IN (%s)
                """.formatted(placeholders), args.toArray());
    }

    @Override
    public int deleteEvidence(UUID submissionId, int round) {
        return jdbc.update("DELETE FROM kyc_round_evidence WHERE submission_id = ? AND round = ?",
                submissionId, round);
    }

    @Override
    public List<UUID> unreferencedFileIds(UUID submissionId) {
        return jdbc.query("""
                SELECT f.id FROM kyc_file f
                 WHERE f.submission_id = ?
                   AND NOT EXISTS (SELECT 1 FROM kyc_round_evidence e WHERE e.file_id = f.id)
                """, (rs, n) -> rs.getObject("id", UUID.class), submissionId);
    }

    @Override
    public int countOtherCustomersWithNationalId(UUID customerId, Collection<String> signupHashes,
                                                 String canonicalHmac) {
        // Never an empty IN (): the canonical HMAC is always one of the spellings anyway.
        Collection<String> hashes = signupHashes.isEmpty() ? List.of(canonicalHmac) : signupHashes;
        List<Object> args = new ArrayList<>(hashes);
        args.add(customerId);
        args.add(canonicalHmac);
        args.add(customerId);
        String placeholders = String.join(", ", hashes.stream().map(h -> "?").toList());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT c.id AS other FROM customer c
                     WHERE c.national_id_hash IN (%s) AND c.id <> ?
                    UNION
                    SELECT s.customer_id FROM kyc_round r
                      JOIN kyc_submission s ON s.id = r.submission_id
                     WHERE r.national_id_hmac = ? AND s.customer_id <> ?
                ) holders
                """.formatted(placeholders), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<UUID> findLapsed(Instant now, int limit) {
        return jdbc.query("""
                SELECT id FROM kyc_submission
                 WHERE expires_at IS NOT NULL AND expires_at <= ? AND status IN ('draft', 'rejected')
                 ORDER BY expires_at
                 LIMIT ?
                """, (rs, n) -> rs.getObject("id", UUID.class), ts(now), limit);
    }

    @Override
    public List<RoundRef> findRejectedRoundsToPurge(Instant decidedBefore, int limit) {
        return jdbc.query("""
                SELECT submission_id, round FROM kyc_round
                 WHERE decision = 'rejected' AND decided_at <= ? AND files_purged_at IS NULL
                 ORDER BY decided_at
                 LIMIT ?
                """, (rs, n) -> new RoundRef(rs.getObject("submission_id", UUID.class), rs.getInt("round")),
                ts(decidedBefore), limit);
    }

    @Override
    public void markFilesPurged(UUID submissionId, int round, Instant at) {
        jdbc.update("UPDATE kyc_round SET files_purged_at = ? WHERE submission_id = ? AND round = ?",
                ts(at), submissionId, round);
    }

    // --- mapping ------------------------------------------------------------

    private static final RowMapper<KycSubmissionRecord> SUBMISSION = (rs, n) -> new KycSubmissionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            KycTier.parse(rs.getString("target_tier")).orElseThrow(() ->
                    new IllegalStateException("kyc_submission.target_tier holds an unknown tier")),
            KycSubmissionStatus.fromDbValue(rs.getString("status")),
            rs.getInt("current_round"),
            rs.getString("address_ciphertext"),
            instant(rs, "created_at"),
            instant(rs, "submitted_at"),
            instant(rs, "decided_at"),
            instant(rs, "expires_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<KycRoundRecord> ROUND = (rs, n) -> new KycRoundRecord(
            rs.getObject("submission_id", UUID.class),
            rs.getInt("round"),
            instant(rs, "opened_at"),
            instant(rs, "frozen_at"),
            rs.getString("national_id_ciphertext"),
            rs.getString("national_id_hmac"),
            rs.getString("masked_id"),
            SignupMatch.fromDbValue(rs.getString("signup_match")),
            (Integer) rs.getObject("duplicate_count"),
            rs.getString("decision"),
            instant(rs, "decided_at"),
            splitReasons(rs.getString("rejection_reasons")),
            splitKinds(rs.getString("redo")),
            instant(rs, "files_purged_at"));

    static String joinReasons(List<KycRejectionReason> reasons) {
        return reasons == null || reasons.isEmpty() ? null
                : reasons.stream().map(KycRejectionReason::code).collect(Collectors.joining(","));
    }

    static String joinKinds(Set<KycFileKind> kinds) {
        return kinds == null || kinds.isEmpty() ? null
                : EnumSet.copyOf(kinds).stream().map(KycFileKind::code).collect(Collectors.joining(","));
    }

    static List<KycRejectionReason> splitReasons(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(code -> KycRejectionReason.fromCode(code).orElse(KycRejectionReason.OTHER))
                .toList();
    }

    static Set<KycFileKind> splitKinds(String value) {
        Set<KycFileKind> out = EnumSet.noneOf(KycFileKind.class);
        if (value != null && !value.isBlank()) {
            for (String code : value.split(",")) {
                KycFileKind.fromCode(code).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static <T> Optional<T> one(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireOne(int updated, String what) {
        if (updated != 1) {
            throw new IllegalStateException("expected to update exactly one " + what + ", updated " + updated);
        }
    }
}
