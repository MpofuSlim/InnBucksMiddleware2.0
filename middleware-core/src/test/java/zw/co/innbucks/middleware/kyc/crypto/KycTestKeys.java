package zw.co.innbucks.middleware.kyc.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/** Random throwaway keys for tests — never a fixed key in the repo. */
public final class KycTestKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private KycTestKeys() {
    }

    public static String newKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** A one-key ring, v1 current. */
    public static KycKeyRing ring() {
        return KycKeyRing.parse("test", "current=v1\nv1=" + newKey() + "\n");
    }

    public static KycCipher cipher() {
        return new KycCipher(ring());
    }
}
