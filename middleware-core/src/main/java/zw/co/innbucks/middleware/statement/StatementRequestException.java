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

    public static StatementRequestException invalidPeriod(String message) {
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

    public static StatementRequestException tooLarge(int maxRows) {
        return new StatementRequestException("statement_too_large",
                "This period has more than " + maxRows + " entries. Ask for a shorter period.",
                true);
    }

    /**
     * The loan variant: a loan statement has no period ceiling (it defaults
     * to the life of the loan), so the only cap is the entry count — and the
     * fix is a narrower, more RECENT period, because the walk starts from
     * today.
     */
    public static StatementRequestException loanTooLarge(int maxRows) {
        return new StatementRequestException("statement_too_large",
                "This loan has more than " + maxRows + " transactions in reach of the requested "
                        + "period. Ask for a shorter, more recent period.",
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
