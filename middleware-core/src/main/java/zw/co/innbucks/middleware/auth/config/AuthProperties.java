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
        BruteForce bruteForce
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
