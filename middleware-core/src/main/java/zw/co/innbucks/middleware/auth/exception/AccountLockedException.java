package zw.co.innbucks.middleware.auth.exception;

import lombok.Getter;

import java.time.Instant;

@Getter
public class AccountLockedException extends AuthException {

    private final Instant lockedUntil;

    public AccountLockedException(Instant lockedUntil) {
        super("Account is locked");
        this.lockedUntil = lockedUntil;
    }
}
