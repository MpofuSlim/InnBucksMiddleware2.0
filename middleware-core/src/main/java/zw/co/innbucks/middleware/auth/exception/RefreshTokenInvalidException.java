package zw.co.innbucks.middleware.auth.exception;

public class RefreshTokenInvalidException extends AuthException {

    public RefreshTokenInvalidException() {
        super("Refresh token is invalid or expired");
    }
}
