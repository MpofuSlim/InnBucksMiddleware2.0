package zw.co.innbucks.middleware.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "innbucks.auth")
public record AuthProperties(

        @NotBlank
        @Size(min = 32, message = "JWT signing key must be at least 32 characters (256 bits) for HS256")
        String signingKey,

        /**
         * Signs OTP verification / step-up tokens ONLY. Deliberately a
         * DIFFERENT key from {@code signingKey}: with one shared key, whoever
         * can mint access tokens can also mint PIN-reset verification tokens —
         * a straight account-takeover primitive. ProductionSecretsGuard
         * refuses to boot if the two keys are equal.
         */
        @NotBlank
        @Size(min = 32, message = "Verification-token signing key must be at least 32 characters (256 bits) for HS256")
        String verificationSigningKey,

        @NotBlank
        String audience,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl,

        @NotNull
        BruteForce bruteForce,

        /**
         * Access-token signing algorithm: HS256 (default, the shared-secret
         * status quo) or RS256. The RS256 migration is staged like the
         * ticketing fleet's: (1) provision {@code publicKey} everywhere and
         * roll — verifiers then accept both algorithms; (2) flip this to
         * RS256 (+ {@code privateKey}) and roll — minting changes and the
         * security win lands (a leaked verifier key can no longer MINT);
         * (3) after max token TTL, retire the HS256 secret.
         */
        String signingAlgorithm,

        /** PEM PKCS#8 RSA private key ("BEGIN PRIVATE KEY"). Required iff signingAlgorithm=RS256. */
        String privateKey,

        /**
         * PEM X.509 RSA public key ("BEGIN PUBLIC KEY"). Optional: when set,
         * the decoder accepts RS256 tokens (selected by the token's own alg
         * header) alongside HS256. Without it, RS256 tokens are rejected.
         */
        String publicKey,

        /** Optional key id stamped into the RS256 JWS header ({@code kid}) for future rotation. */
        String keyId
) {

    public record BruteForce(

            @NotNull
            int maxFailedAttemptsBeforeLock,

            @NotNull
            Duration lockDuration,

            @NotNull
            Duration backoffBase
    ) {
    }
}
