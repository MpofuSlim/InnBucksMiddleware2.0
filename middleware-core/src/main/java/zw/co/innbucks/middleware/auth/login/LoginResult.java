package zw.co.innbucks.middleware.auth.login;

import java.time.Duration;

public record LoginResult(
        String accessToken,
        String refreshToken,
        Duration accessTokenTtl
) {
}
