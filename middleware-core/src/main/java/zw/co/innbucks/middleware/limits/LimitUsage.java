package zw.co.innbucks.middleware.limits;

/**
 * What a customer has already sent in the current windows, read from the
 * ledger: withdrawals + transfers, every status except FAILED, in the cell
 * currency. {@code *Processing} is the part still PENDING / SUBMITTED /
 * UNKNOWN — included in {@code *Used}, reported separately so a screen can
 * say "X is still processing" instead of showing an unexplained figure.
 *
 * @param dayCount number of such payments made today (any non-FAILED status)
 */
public record LimitUsage(long dayUsedMinor, long dayProcessingMinor, long dayCount,
                         long monthUsedMinor, long monthProcessingMinor) {

    /** Nothing sent — also what a check that needs no usage passes around. */
    public static final LimitUsage NONE = new LimitUsage(0, 0, 0, 0, 0);
}
