package zw.co.innbucks.middleware.auth.exception;

public class RefreshTokenReplayException extends AuthException {

    public RefreshTokenReplayException() {
        super("Refresh token replay detected; token family revoked");
    }
}
