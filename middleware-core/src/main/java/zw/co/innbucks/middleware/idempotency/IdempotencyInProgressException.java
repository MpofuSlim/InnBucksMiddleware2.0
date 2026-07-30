package zw.co.innbucks.middleware.idempotency;

/**
 * A concurrent request holding the same idempotency key is still executing
 * (its claim row is younger than the in-progress grace). Maps to HTTP 409 —
 * the caller retries with the SAME key and gets the winner's cached response.
 */
public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String key) {
        super("A request with this idempotency key is already in progress: " + key);
    }
}
