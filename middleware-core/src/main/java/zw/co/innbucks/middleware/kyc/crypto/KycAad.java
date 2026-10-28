package zw.co.innbucks.middleware.kyc.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Additional authenticated data for one sealed value: WHICH row and column it
 * belongs to, and — for a file — whose evidence it is. GCM refuses to open a
 * ciphertext under any other binding, so a blob copied from one row into
 * another fails to decrypt instead of quietly showing the wrong evidence.
 *
 * <p>For a file the binding has to reach past its own row, because a round
 * SHOWS a file by REFERENCE (a {@code kyc_round_evidence} row pointing at it):
 * someone with database write access swapping an approved customer's ID photo
 * into their own submission does not move a blob, they re-point that
 * reference. So a file is bound to the submission, round and kind it was
 * uploaded as, and the reader rebuilds the binding from what it EXPECTS — the
 * submission it is showing and the evidence row's kind — never from the file
 * row. Every one of those values is fixed for the life of a file (a rejected
 * round's file is carried into the next round of the SAME submission and
 * kind), so carry-forward still opens.
 *
 * <p>The binding is part of the format: changing a label here makes every row
 * sealed under the old one unreadable. Add new labels; never edit these.
 */
public final class KycAad {

    private static final String PREFIX = "innbucks-kyc|";

    private final String value;

    private KycAad(String value) {
        this.value = value;
    }

    /** The typed national ID of one round. */
    public static KycAad nationalId(UUID submissionId, int round) {
        return new KycAad(PREFIX + "kyc_round.national_id|" + submissionId + "|" + round);
    }

    /** The typed residential address of one (enhanced) submission. */
    public static KycAad address(UUID submissionId) {
        return new KycAad(PREFIX + "kyc_submission.address|" + submissionId);
    }

    /**
     * The bytes of one stored file: uploaded into {@code submissionId} in
     * {@code round} as {@code kind} (the database code, e.g. {@code id_front}).
     */
    public static KycAad file(UUID submissionId, int round, String kind, UUID fileId) {
        return new KycAad(PREFIX + "kyc_file.content|" + submissionId + "|" + round + "|" + kind + "|" + fileId);
    }

    byte[] bytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return value;
    }
}
