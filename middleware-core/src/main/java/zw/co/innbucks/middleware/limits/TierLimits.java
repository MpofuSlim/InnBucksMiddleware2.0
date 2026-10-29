package zw.co.innbucks.middleware.limits;

/**
 * One tier's configured limits. Every component is nullable, and {@code null}
 * means ABSENT: no limit of that kind for this tier. Money values are minor
 * units of the cell currency; the count is a number of payments.
 *
 * <p>Pure value — validation lives in {@link PaymentLimitsPolicyLoader}, which
 * is the only producer of the instances the payment path sees.
 */
public record TierLimits(
        Long perPaymentMinor,
        Long dailyMinor,
        Long monthlyMinor,
        Integer dailyCount
) {

    private static final TierLimits NONE = new TierLimits(null, null, null, null);

    /** No limit of any kind — the dormant state. */
    public static TierLimits none() {
        return NONE;
    }

    /** The configured value of {@code kind}, widened to long; {@code null} when absent. */
    public Long get(LimitKind kind) {
        return switch (kind) {
            case PER_PAYMENT -> perPaymentMinor;
            case DAILY -> dailyMinor;
            case MONTHLY -> monthlyMinor;
            case DAILY_COUNT -> dailyCount == null ? null : dailyCount.longValue();
        };
    }

    /** A copy with {@code kind} set to {@code value} ({@code null} clears it). */
    public TierLimits with(LimitKind kind, Long value) {
        return switch (kind) {
            case PER_PAYMENT -> new TierLimits(value, dailyMinor, monthlyMinor, dailyCount);
            case DAILY -> new TierLimits(perPaymentMinor, value, monthlyMinor, dailyCount);
            case MONTHLY -> new TierLimits(perPaymentMinor, dailyMinor, value, dailyCount);
            case DAILY_COUNT -> new TierLimits(perPaymentMinor, dailyMinor, monthlyMinor,
                    value == null ? null : Math.toIntExact(value));
        };
    }

    /** True when no kind is configured: nothing to check, nothing to lock. */
    public boolean isDormant() {
        return perPaymentMinor == null && dailyMinor == null && monthlyMinor == null && dailyCount == null;
    }

    /**
     * True when a check needs the customer's ledger usage (and so, on the
     * binding path, the per-customer lock). A per-payment cap alone is
     * stateless: it is decided from the request body.
     */
    public boolean needsUsage() {
        return dailyMinor != null || monthlyMinor != null || dailyCount != null;
    }

    /** True when at least one MONEY kind is configured. */
    public boolean hasMoneyLimit() {
        return perPaymentMinor != null || dailyMinor != null || monthlyMinor != null;
    }

    /** Log/audit rendering: {@code per_payment=100000 daily=none ...}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (LimitKind kind : LimitKind.values()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            Long value = get(kind);
            sb.append(kind.wireName()).append('=').append(value == null ? "none" : value);
        }
        return sb.toString();
    }
}
