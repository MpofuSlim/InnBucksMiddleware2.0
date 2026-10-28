package zw.co.innbucks.middleware.kyc.crypto;

/**
 * A KYC envelope could not be opened (or sealed). Never carries plaintext or
 * key material — the message only says which check failed.
 */
public class KycCryptoException extends IllegalStateException {

    public KycCryptoException(String message) {
        super("KYC envelope: " + message);
    }
}
