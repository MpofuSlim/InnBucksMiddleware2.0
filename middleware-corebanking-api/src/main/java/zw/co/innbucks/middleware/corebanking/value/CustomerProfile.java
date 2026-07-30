package zw.co.innbucks.middleware.corebanking.value;

/**
 * The trimmed profile view the middleware actually consumes. Deliberately NOT
 * a mirror of any core's client DTO — unknown upstream fields are ignored at
 * the adapter.
 */
public record CustomerProfile(
        CoreCustomerRef ref,
        String firstName,
        String lastName,
        String status
) {
}
