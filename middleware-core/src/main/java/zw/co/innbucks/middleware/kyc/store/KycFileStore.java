package zw.co.innbucks.middleware.kyc.store;

import zw.co.innbucks.middleware.kyc.KycFileKind;
import zw.co.innbucks.middleware.kyc.image.ProcessedImage;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Where KYC image BYTES live. Every file keeps a {@code kyc_file} row (id,
 * round, kind, dimensions, size) that the round evidence references; only the
 * bytes move between implementations.
 *
 * <p>Today: {@link PostgresKycFileStore}, AES-GCM sealed into
 * {@code kyc_file.content_ciphertext}, bound to the submission, round and
 * kind it was uploaded as ({@code KycAad.file}). The interface is the seam for an
 * S3 + SSE-KMS implementation (the bytes as an object, the row holding its
 * key) once volume makes the database the wrong place — see CLAUDE.md for
 * when. An implementation must seal before the bytes leave the process and
 * must never log them.
 */
public interface KycFileStore {

    /** Seals and stores one processed image; joins the caller's transaction. */
    StoredKycFile store(UUID fileId, UUID submissionId, int round, KycFileKind kind,
                        ProcessedImage image, Instant at);

    /**
     * The plaintext JPEG a round of {@code submissionId} shows through
     * {@code evidence} (for the staff read surface a later change adds). The
     * seal is checked against THAT expectation — this submission, the
     * evidence row's kind, the file's upload round — so a file of another
     * submission or kind, however the rows point at it, does not open
     * ({@link zw.co.innbucks.middleware.kyc.crypto.KycCryptoException}).
     */
    byte[] read(UUID submissionId, KycEvidence evidence);

    /** Deletes the rows and their bytes; returns how many went. */
    int delete(Collection<UUID> fileIds);

    record StoredKycFile(UUID id, KycFileKind kind, int round, int width, int height, int byteSize) {
    }
}
