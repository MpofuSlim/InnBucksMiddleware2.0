package zw.co.innbucks.middleware.limits;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import zw.co.innbucks.middleware.customer.KycTier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 422 limit_exceeded body the app branches on: errorCode plus the refusal
 * fields at the top level, the money pair OR the count pair (never both), and
 * resetsAt / nextTier present as explicit nulls where they do not apply.
 */
class LimitExceededProblemTest {

    @Test
    void aDailyRefusalCarriesTheMoneyFieldsAndTheReset() {
        ProblemDetail body = LimitExceededProblem.of(LimitRefusal.money(LimitKind.DAILY, 150000, 137500,
                "2026-10-29T00:00:00+02:00", KycTier.BASIC));

        assertThat(body.getStatus()).isEqualTo(422);
        assertThat(body.getTitle()).isEqualTo("Payment limit reached");
        assertThat(body.getProperties())
                .containsEntry("errorCode", "limit_exceeded")
                .containsEntry("limit", "daily")
                .containsEntry("limitMinor", 150000L)
                .containsEntry("remainingMinor", 137500L)
                .containsEntry("resetsAt", "2026-10-29T00:00:00+02:00")
                .containsEntry("kycTier", "basic")
                .containsEntry("nextTier", "standard")
                .doesNotContainKeys("limitCount", "remainingCount");
    }

    @Test
    void aPerPaymentRefusalSaysItNeverResets() {
        ProblemDetail body = LimitExceededProblem.of(LimitRefusal.money(LimitKind.PER_PAYMENT, 100000, 100000,
                null, KycTier.STANDARD));

        assertThat(body.getProperties())
                .containsEntry("limit", "per_payment")
                .containsEntry("limitMinor", 100000L)
                .containsEntry("remainingMinor", 100000L)
                .containsEntry("resetsAt", null)
                .containsEntry("nextTier", "enhanced");
        assertThat(body.getProperties()).containsKey("resetsAt");
    }

    @Test
    void aCountRefusalCarriesTheCountFieldsAndTheTopTierHasNoNextTier() {
        ProblemDetail body = LimitExceededProblem.of(LimitRefusal.count(10, 0, "2026-10-29T00:00:00+02:00",
                KycTier.ENHANCED));

        assertThat(body.getProperties())
                .containsEntry("limit", "daily_count")
                .containsEntry("limitCount", 10L)
                .containsEntry("remainingCount", 0L)
                .containsEntry("kycTier", "enhanced")
                .containsEntry("nextTier", null)
                .doesNotContainKeys("limitMinor", "remainingMinor");
        assertThat(body.getProperties()).containsKey("nextTier");
    }
}
