package zw.co.innbucks.middleware.common.msisdn;

/**
 * Mask MSISDNs before they hit structured logs. Centralised + tiny so every
 * call site uses the same shape — a single grep on production logs for full
 * MSISDNs should turn up nothing.
 *
 * <p>The mask format keeps the dialling country code (so a Kenya / Tanzania
 * / Uganda deployment is still distinguishable in mixed-stream logs) and
 * the last three digits (so support agents can correlate with a customer
 * inquiry that quotes the visible suffix). Everything in between is
 * replaced with asterisks.
 *
 * <pre>
 *   normalise("+254712345678")  -> "+254*****678"
 *   normalise(null / "")         -> "[missing]"
 *   normalise("0712")            -> "***"
 * </pre>
 */
public final class MsisdnMasking {

    private MsisdnMasking() {
    }

    private static final String MISSING = "[missing]";
    private static final String TOO_SHORT = "***";
    private static final int KEEP_LAST = 3;

    public static String mask(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return MISSING;
        }
        // E.164 starts with `+CC...`. The CC is variable-length (1–3 digits)
        // but Kenya's is 254; we keep the first 4 chars (`+CCC`) when present
        // and the last 3 digits, asterisking the middle.
        int len = msisdn.length();
        if (len <= KEEP_LAST + 1) {
            return TOO_SHORT;
        }
        String head = msisdn.startsWith("+") && len >= 5 ? msisdn.substring(0, 4) : "";
        String tail = msisdn.substring(len - KEEP_LAST);
        int maskLen = len - head.length() - KEEP_LAST;
        if (maskLen <= 0) {
            return TOO_SHORT;
        }
        return head + "*".repeat(maskLen) + tail;
    }
}
