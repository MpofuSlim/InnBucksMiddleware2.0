package zw.co.innbucks.middleware.statement;

/**
 * The caller asked for a statement this endpoint will not produce — a
 * malformed period, one longer than the configured ceiling, or one whose
 * entry count exceeds the inline-render cap. Each carries a stable
 * {@code errorCode} for the app to branch on and a human-readable detail.
 */
public class StatementRequestException extends RuntimeException {

    private final String errorCode;
    private final boolean unprocessable;

    private StatementRequestException(String errorCode, String message, boolean unprocessable) {
        super(message);
        this.errorCode = errorCode;
        this.unprocessable = unprocessable;
    }

    static StatementRequestException invalidPeriod(String message) {
        return new StatementRequestException("statement_period_invalid", message, false);
    }

    static StatementRequestException periodTooLong(int maxDays) {
        return new StatementRequestException("statement_period_too_long",
                "Statements cover at most " + maxDays + " days per request. Ask for a shorter period.",
                false);
    }

    public static StatementRequestException invalidFormat(String requested) {
        return new StatementRequestException("statement_format_invalid",
                "Unknown format '" + requested + "'. Use json, pdf or csv.", false);
    }

    static StatementRequestException tooLarge(int maxRows) {
        return new StatementRequestException("statement_too_large",
                "This period has more than " + maxRows + " entries. Ask for a shorter period.",
                true);
    }

    static StatementRequestException unsupportedAccount() {
        return new StatementRequestException("statement_unsupported_account",
                "This account carries no external reference, so its transactions cannot be read "
                        + "through this middleware yet. Statements for branch-created accounts are a "
                        + "planned extension.",
                true);
    }

    public String errorCode() {
        return errorCode;
    }

    /** True when the request was well-formed but the result cannot be produced — a 422, not a 400. */
    public boolean unprocessable() {
        return unprocessable;
    }
}
