package zw.co.innbucks.middleware.statement;

/**
 * The statement could not be built with honest balances — the core answered,
 * but without the running-balance data the balance policy needs. Maps to 503:
 * the request was valid and may succeed later; a statement with invented
 * numbers is never the fallback.
 */
public class StatementUnavailableException extends RuntimeException {

    public StatementUnavailableException(String message) {
        super(message);
    }
}
