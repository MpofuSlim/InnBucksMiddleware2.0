package zw.co.innbucks.middleware.otp;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "innbucks.otp")
public record OtpProperties(

        /** OTP code lifetime. After this, the code is rejected even if attempts remain. */
        @NotNull
        Duration ttl,

        /** Max verify attempts per challenge before the row is invalidated. */
        @Min(1)
        int maxAttempts,

        /** Short-lived verification-token TTL (the JWT returned by /auth/otp/verify). */
        @NotNull
        Duration verificationTokenTtl,

        /**
         * DEVELOPMENT ONLY. When set to a non-blank 4-8 digit string the OTP
         * generator emits this fixed value instead of a random one. Useful
         * before the SMS provider is wired up. The app logs a loud WARN on
         * every startup when this is non-null and refuses to start when the
         * 'prod' profile is active.
         */
        @Pattern(regexp = "^$|^[0-9]{4,8}$",
                message = "OTP fixed-code must be empty or 4-8 digits")
        String fixedCode
) {
}
