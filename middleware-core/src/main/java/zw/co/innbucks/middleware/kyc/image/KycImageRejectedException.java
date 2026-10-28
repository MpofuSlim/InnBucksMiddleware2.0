package zw.co.innbucks.middleware.kyc.image;

import java.util.Locale;

/**
 * An uploaded file is not an image we will keep. Maps to 400
 * {@code kyc_file_invalid} with {@link Reason#code()} as {@code reason}, so the
 * app can tell "retake it closer" ({@code too_small}) from "this is not a
 * camera JPEG" ({@code not_jpeg}). The message describes the structure only,
 * never the bytes.
 */
public class KycImageRejectedException extends RuntimeException {

    public enum Reason {
        /** Not a JPEG by its bytes (whatever the Content-Type said). */
        NOT_JPEG,
        /** A JPEG that is truncated or malformed. */
        CORRUPT,
        /** A JPEG we do not decode: progressive, arithmetic-coded, lossless, 12-bit, CMYK. */
        UNSUPPORTED_ENCODING,
        /** Declared width x height over the pixel cap — refused before decoding. */
        TOO_MANY_PIXELS,
        /** Shorter side under the legibility minimum. */
        TOO_SMALL;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Reason reason;

    public KycImageRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
