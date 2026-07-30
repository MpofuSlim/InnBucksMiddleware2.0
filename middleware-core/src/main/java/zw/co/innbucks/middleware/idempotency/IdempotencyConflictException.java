package zw.co.innbucks.middleware.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency key reused with a different request body: " + key);
    }
}
