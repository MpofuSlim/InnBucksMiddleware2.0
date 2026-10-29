package zw.co.innbucks.middleware.kyc.image;

/**
 * Every upload place was taken (refused before the body is read), or every
 * decode slot stayed busy for the whole wait. Maps to 503
 * {@code kyc_upload_busy} with Retry-After: nothing was stored and the upload
 * can simply be sent again.
 */
public class KycUploadBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public KycUploadBusyException(long retryAfterSeconds) {
        super("All KYC upload places or decode slots are busy");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
