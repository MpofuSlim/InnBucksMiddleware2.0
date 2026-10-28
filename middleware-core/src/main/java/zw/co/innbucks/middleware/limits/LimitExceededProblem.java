package zw.co.innbucks.middleware.limits;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * The 422 {@code limit_exceeded} body. Fields sit at the TOP level of the
 * problem, beside {@code errorCode}, like every other error this API returns:
 *
 * <pre>
 * limit           per_payment | daily | monthly | daily_count
 * limitMinor      money kinds only          limitCount      daily_count only
 * remainingMinor  money kinds only          remainingCount  daily_count only
 *                 (the most that passes NOW = /me/limits sendableNowMinor;
 *                 for per_payment it can be below limitMinor)
 * resetsAt        ISO-8601 at the market offset; null for per_payment
 * kycTier         the tier the limit belongs to
 * nextTier        the tier above; null at the top ("Raise my limits" only when set)
 * </pre>
 *
 * The detail text is generic on purpose: the app words the message from
 * {@code limit} and the numbers, in the customer's language and currency format.
 */
public final class LimitExceededProblem {

    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    private LimitExceededProblem() {
    }

    public static ProblemDetail of(LimitRefusal refusal) {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        body.setType(PROBLEM_TYPE);
        body.setTitle("Payment limit reached");
        body.setDetail(switch (refusal.kind()) {
            case PER_PAYMENT -> "This amount is more than you can send in one payment.";
            case DAILY -> "This payment would take you over your daily sending limit.";
            case MONTHLY -> "This payment would take you over your monthly sending limit.";
            case DAILY_COUNT -> "You have made as many payments as you can today.";
        });
        body.setProperty("errorCode", "limit_exceeded");
        body.setProperty("limit", refusal.kind().wireName());
        if (refusal.kind().isMoney()) {
            body.setProperty("limitMinor", refusal.limitMinor());
            body.setProperty("remainingMinor", refusal.remainingMinor());
        } else {
            body.setProperty("limitCount", refusal.limitCount());
            body.setProperty("remainingCount", refusal.remainingCount());
        }
        body.setProperty("resetsAt", refusal.resetsAt());
        body.setProperty("kycTier", refusal.kycTier().dbValue());
        body.setProperty("nextTier", refusal.nextTier() == null ? null : refusal.nextTier().dbValue());
        return body;
    }
}
