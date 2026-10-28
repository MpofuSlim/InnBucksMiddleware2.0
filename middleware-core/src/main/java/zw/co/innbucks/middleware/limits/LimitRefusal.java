package zw.co.innbucks.middleware.limits;

import zw.co.innbucks.middleware.customer.KycTier;

/**
 * Why one payment was refused — the fields of the 422 {@code limit_exceeded}
 * body. Exactly one of the money pair ({@code limitMinor}/{@code remainingMinor})
 * or the count pair ({@code limitCount}/{@code remainingCount}) is set.
 *
 * @param remainingMinor for daily/monthly: what is left in the period. For
 *                       per_payment: the limit itself — a single payment's
 *                       allowance never depletes; {@code GET /me/limits}
 *                       {@code sendableNowMinor} is the combined figure.
 * @param resetsAt       when the allowance comes back, ISO-8601 at the market
 *                       offset; {@code null} for per_payment (it never resets)
 * @param nextTier       the tier above, or {@code null} at the top — the app
 *                       offers "Raise my limits" only when it is present
 */
public record LimitRefusal(
        LimitKind kind,
        Long limitMinor,
        Long remainingMinor,
        Long limitCount,
        Long remainingCount,
        String resetsAt,
        KycTier kycTier,
        KycTier nextTier
) {

    static LimitRefusal money(LimitKind kind, long limit, long remaining, String resetsAt, KycTier tier) {
        return new LimitRefusal(kind, limit, remaining, null, null, resetsAt, tier, tier.next());
    }

    static LimitRefusal count(long limit, long remaining, String resetsAt, KycTier tier) {
        return new LimitRefusal(LimitKind.DAILY_COUNT, null, null, limit, remaining, resetsAt, tier, tier.next());
    }
}
