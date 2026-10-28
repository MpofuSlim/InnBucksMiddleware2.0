package zw.co.innbucks.middleware.kyc;

import java.util.Locale;

/**
 * Does the ID typed for KYC match the one given at sign-up? A signal for the
 * reviewer only — never shown to the customer, never a refusal.
 *
 * <p>The sign-up hash was taken over the number exactly as typed (trimmed
 * only), so a {@code MATCH} is certain while a {@code NO_MATCH} can still be
 * the same number written with an unusual separator: the common spellings are
 * tried first, not every possible one.
 */
public enum SignupMatch {
    MATCH,
    NO_MATCH,
    /** No ID was given at sign-up. */
    NONE_GIVEN;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SignupMatch fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
