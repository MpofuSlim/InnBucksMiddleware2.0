package zw.co.innbucks.middleware.kyc.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;
import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.crypto.KycAad;
import zw.co.innbucks.middleware.kyc.crypto.KycCipher;
import zw.co.innbucks.middleware.kyc.image.ProcessedImage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * KYC images in the middleware's own Postgres, each sealed with
 * {@link KycCipher} under a binding to the submission, round and kind it was
 * uploaded as — so the database, its dumps and its volume tarballs hold only
 * ciphertext, and neither a blob moved onto another row nor an evidence row
 * re-pointed at another customer's file opens.
 *
 * <p>The cost of this choice is volume in the stop-the-world backup:
 * {@code backup-cell.sh} stops the middleware for the dump, and every
 * gigabyte here is dumped twice (hex in the SQL dump, raw in the volume
 * tarball). Deleted files also linger as dead tuples until VACUUM and in
 * every earlier backup until it ages out. CLAUDE.md says when to move to
 * object storage behind {@link KycFileStore}.
 */
@Repository
@ConditionalOnKycEnabled
public class PostgresKycFileStore implements KycFileStore {

    private final JdbcTemplate jdbc;
    private final KycCipher cipher;

    public PostgresKycFileStore(JdbcTemplate jdbc, KycCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public StoredKycFile store(UUID fileId, UUID submissionId, int round, KycFileKind kind,
                               ProcessedImage image, Instant at) {
        byte[] sealed = cipher.seal(image.jpeg(), KycAad.file(submissionId, round, kind.code(), fileId));
        jdbc.update("""
                INSERT INTO kyc_file
                    (id, submission_id, round, kind, content_ciphertext, width, height, byte_size, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fileId, submissionId, round, kind.code(), sealed, image.width(), image.height(),
                image.byteSize(), Timestamp.from(at));
        return new StoredKycFile(fileId, kind, round, image.width(), image.height(), image.byteSize());
    }

    @Override
    public byte[] read(UUID submissionId, KycEvidence evidence) {
        List<byte[]> rows = jdbc.query("SELECT content_ciphertext FROM kyc_file WHERE id = ?",
                (rs, n) -> rs.getBytes("content_ciphertext"), evidence.fileId());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no kyc_file " + evidence.fileId());
        }
        // The binding comes from what the caller expects, not from the row:
        // a row re-labelled or re-pointed by a database writer does not open.
        return cipher.open(rows.get(0), KycAad.file(submissionId, evidence.fileRound(), evidence.kind().code(),
                evidence.fileId()));
    }

    @Override
    public int delete(Collection<UUID> fileIds) {
        if (fileIds.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>(fileIds);
        String placeholders = String.join(", ", fileIds.stream().map(id -> "?").toList());
        return jdbc.update("DELETE FROM kyc_file WHERE id IN (" + placeholders + ")", args.toArray());
    }
}
