package zw.co.innbucks.middleware.auth.exception;

import lombok.Getter;

@Getter
public class BackoffActiveException extends AuthException {

    private final long retryAfterSeconds;

    public BackoffActiveException(long retryAfterSeconds) {
        super("Too many recent failed attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
