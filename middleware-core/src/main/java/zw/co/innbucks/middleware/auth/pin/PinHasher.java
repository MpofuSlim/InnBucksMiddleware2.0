package zw.co.innbucks.middleware.auth.pin;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PinHasher {

    // Argon2id with OWASP 2024 baseline parameters tuned for short PINs:
    // saltLen=16, hashLen=32, parallelism=1, memory=64 MiB, iterations=3.
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);

    public String hash(String rawPin) {
        if (rawPin == null) {
            throw new IllegalArgumentException("PIN cannot be null");
        }
        return encoder.encode(rawPin);
    }

    public boolean matches(String rawPin, String storedHash) {
        if (rawPin == null || storedHash == null) {
            return false;
        }
        return encoder.matches(rawPin, storedHash);
    }
}
