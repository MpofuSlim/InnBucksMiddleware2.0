package zw.co.innbucks.middleware.auth.exception;

public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
