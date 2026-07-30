package zw.co.innbucks.middleware.otp;

import java.time.Duration;

/**
 * Token issued by {@link VerificationTokenIssuer} after a successful OTP
 * verification. It is a signed JWT carrying a single-use grant ("this MSISDN
 * just proved phone ownership for purpose X") with a short TTL.
 * The actual token string is opaque to callers.
 */
public record VerificationToken(

        String token,
        Duration ttl
) {
}
