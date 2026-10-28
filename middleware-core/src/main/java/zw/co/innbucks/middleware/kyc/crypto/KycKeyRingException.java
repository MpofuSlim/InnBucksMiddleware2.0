package zw.co.innbucks.middleware.kyc.crypto;

/** The KYC key file is missing, unreadable or malformed. Thrown at boot; never carries key material. */
public class KycKeyRingException extends IllegalStateException {

    public KycKeyRingException(String message) {
        super("KYC key ring: " + message);
    }
}
