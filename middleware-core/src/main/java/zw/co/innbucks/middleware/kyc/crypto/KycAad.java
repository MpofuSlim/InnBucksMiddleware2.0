package zw.co.innbucks.middleware.kyc.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Additional authenticated data for one sealed value: WHICH row and column it
 * belongs to. GCM refuses to open a ciphertext under any other binding, so a
 * blob copied from one row into another — someone with database write access
 * swapping an approved customer's ID photo into their own submission, say —
 * fails to decrypt instead of quietly showing the wrong evidence.
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

    /** The bytes of one stored file. */
    public static KycAad file(UUID fileId) {
        return new KycAad(PREFIX + "kyc_file.content|" + fileId);
    }

    byte[] bytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return value;
    }
}
