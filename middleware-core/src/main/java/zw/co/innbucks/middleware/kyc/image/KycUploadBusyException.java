package zw.co.innbucks.middleware.kyc.image;

/**
 * Every decode slot stayed busy for the whole wait. Maps to 503
 * {@code kyc_upload_busy} with Retry-After: the upload was not read into the
 * store and can simply be sent again.
 */
public class KycUploadBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public KycUploadBusyException(long retryAfterSeconds) {
        super("All KYC image decode slots are busy");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
