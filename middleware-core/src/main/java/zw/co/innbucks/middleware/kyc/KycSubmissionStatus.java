package zw.co.innbucks.middleware.kyc;

import java.util.Locale;

/**
 * Where a submission stands. {@code draft} and a {@code rejected} submission
 * with a round reopened for redo are the customer's to finish (both have an
 * {@code expires_at}); {@code pending_review} waits for staff;
 * {@code approved} is final; {@code expired} means the customer did not finish
 * in time and its unsubmitted files are gone.
 */
public enum KycSubmissionStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static KycSubmissionStatus fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
