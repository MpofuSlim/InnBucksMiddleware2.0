package zw.co.innbucks.middleware.kyc;

import java.util.Locale;
import java.util.Optional;

/** The images a KYC submission can carry. {@link #code()} is the wire and column value. */
public enum KycFileKind {
    ID_FRONT,
    ID_BACK,
    SELFIE,
    PROOF_OF_ADDRESS;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Exact wire code only ({@code id_front}); anything else is empty. */
    public static Optional<KycFileKind> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (KycFileKind kind : values()) {
            if (kind.code().equals(code)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** The selfie is a face; everything else is a document with small print. */
    public boolean isDocument() {
        return this != SELFIE;
    }
}
