package zw.co.innbucks.middleware.limits;

/**
 * The kinds of per-tier payment limit. Each tier in the policy file may carry
 * any subset; an ABSENT kind means "no limit of that kind for that tier".
 *
 * <p>The wire name is the policy-file key, the {@code limit} field of a 422
 * {@code limit_exceeded} body and the {@code kind} of a {@code GET /me/limits}
 * entry — one spelling everywhere, so the file a reviewer reads is the
 * vocabulary the app branches on.
 */
public enum LimitKind {

    /** Largest single withdrawal or transfer, in minor units. Never depletes. */
    PER_PAYMENT("per_payment", true),

    /** Sum of withdrawals + transfers made in the market's calendar day, minor units. */
    DAILY("daily", true),

    /** Sum of withdrawals + transfers made in the market's calendar month, minor units. */
    MONTHLY("monthly", true),

    /** NUMBER of withdrawals + transfers made in the market's calendar day. */
    DAILY_COUNT("daily_count", false);

    private final String wireName;
    private final boolean money;

    LimitKind(String wireName, boolean money) {
        this.wireName = wireName;
        this.money = money;
    }

    public String wireName() {
        return wireName;
    }

    /** True for the kinds measured in minor units; false for the count. */
    public boolean isMoney() {
        return money;
    }

    /** Lookup by policy-file key; {@code null} for anything unknown. */
    public static LimitKind fromWireName(String name) {
        for (LimitKind kind : values()) {
            if (kind.wireName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
