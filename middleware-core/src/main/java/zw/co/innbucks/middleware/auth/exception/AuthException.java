package zw.co.innbucks.middleware.auth.exception;

public abstract class AuthException extends RuntimeException {

    protected AuthException(String message) {
        super(message);
    }
}
