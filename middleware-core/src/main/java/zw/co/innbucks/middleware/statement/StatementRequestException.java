package zw.co.innbucks.middleware.statement;

/**
 * The caller asked for a statement this endpoint will not produce — a
 * malformed period, one longer than the configured ceiling, or one whose
 * entry count exceeds the inline-render cap. Each carries a stable
 * {@code errorCode} for the app to branch on and a human-readable detail.
 */
public class StatementRequestException extends RuntimeException {

    private final String errorCode;
    private final boolean tooLarge;

    private StatementRequestException(String errorCode, String message, boolean tooLarge) {
        super(message);
        this.errorCode = errorCode;
        this.tooLarge = tooLarge;
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

    public String errorCode() {
        return errorCode;
    }

    /** True for the entry-count cap — a 422 (the period was valid, the result is not renderable inline). */
    public boolean tooLarge() {
        return tooLarge;
    }
}
