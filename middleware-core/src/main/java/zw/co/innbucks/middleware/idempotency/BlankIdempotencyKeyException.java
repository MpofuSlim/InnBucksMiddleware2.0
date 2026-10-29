package zw.co.innbucks.middleware.idempotency;

/**
 * The caller sent an {@code Idempotency-Key} header with nothing in it.
 * Spring binds a present-but-blank header as {@code ""}, so the "header
 * missing" path never fires for it; this is that path's twin, rendered by
 * {@code GlobalExceptionHandler} as the same 400 {@code missing_header}.
 * Before it, the blank key surfaced as the catch-all's 500 with an ERROR
 * stack trace on every endpoint that requires the header.
 *
 * <p>An {@link IllegalArgumentException}, so code that already treated a
 * blank key as a programming error keeps compiling and catching it.
 */
public class BlankIdempotencyKeyException extends IllegalArgumentException {

    public BlankIdempotencyKeyException() {
        super("The Idempotency-Key header is blank");
    }
}
