package zw.co.innbucks.middleware.fineract;

import zw.co.innbucks.middleware.corebanking.CoreProvider;
import zw.co.innbucks.middleware.corebanking.exception.CoreServerException;

import java.time.LocalDate;
import java.util.List;

/**
 * Fineract has TWO JSON writers, and they render a {@code LocalDate}
 * differently: the legacy Gson path ({@code toApiJsonSerializer}, used by
 * every endpoint that returns a {@code String}) emits a {@code [yyyy,m,d]}
 * ARRAY, while the Jersey/Jackson path (endpoints that return the DTO
 * directly, e.g. {@code Page<LoanTransactionData>}) emits an ISO string —
 * unless the DTO carries {@code @JsonLocalDateArrayFormat}, in which case
 * the array again. Both shapes are accepted everywhere so a serializer
 * change upstream cannot silently break a statement.
 */
final class FineractDates {

    private FineractDates() {
    }

    /** A date the contract requires — refuses a missing one loudly. */
    static LocalDate parseDate(Object raw) {
        if (raw == null) {
            throw new CoreServerException(CoreProvider.FINERACT,
                    "Fineract returned a transaction with no date", null);
        }
        return parseDateOrNull(raw);
    }

    /** An optional date: null in, null out; any other unrecognised shape is a contract break. */
    static LocalDate parseDateOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> parts && parts.size() >= 3) {
            return LocalDate.of(asInt(parts.get(0)), asInt(parts.get(1)), asInt(parts.get(2)));
        }
        if (raw instanceof CharSequence text) {
            return LocalDate.parse(text);
        }
        throw new CoreServerException(CoreProvider.FINERACT,
                "Unrecognised date shape from Fineract: " + raw, null);
    }

    private static int asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
