package zw.co.innbucks.middleware.customer;

/**
 * A customer's given and family name as the CORE holds them — the only part of
 * {@code CustomerProfile} anything in this middleware actually consumes.
 *
 * <p>Deliberately not the whole profile. The status field on the core's profile
 * is NOT carried here: nothing reads it (every status decision in this service
 * runs off the local {@code customer} row), and a cached copy of a core status
 * would be a standing invitation to gate something on stale state.
 *
 * <p>Either half may be null or blank — a core record with only one name is
 * legal, and every consumer already masks or falls back rather than assuming
 * both are present.
 */
public record CustomerName(String firstName, String lastName) {
}
