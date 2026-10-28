package zw.co.innbucks.middleware.kyc;

import java.util.Locale;
import java.util.Optional;

/**
 * Why a round was rejected, as codes the app words itself (so it can say
 * exactly what to retake). Set by the review step, which a later change adds;
 * this change only stores and shows them.
 */
public enum KycRejectionReason {
    ID_PHOTO_UNREADABLE,
    ID_EXPIRED,
    NAME_MISMATCH,
    FACE_MISMATCH,
    SELFIE_NOT_LIVE,
    ADDRESS_UNREADABLE,
    OTHER;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<KycRejectionReason> fromCode(String code) {
        for (KycRejectionReason reason : values()) {
            if (reason.code().equals(code)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
