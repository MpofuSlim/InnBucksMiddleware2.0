package zw.co.innbucks.middleware.common.msisdn;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;

import java.util.regex.Pattern;

/**
 * Zimbabwe (+263) MSISDN normalizer. Accepts the three shapes customers
 * actually type — {@code +263771234567}, {@code 263771234567} and
 * {@code 0771234567} — and emits the canonical E.164 form.
 *
 * <p>Mobile prefixes (the digit after the leading {@code 7}): NetOne 71,
 * Telecel 73, Econet 77 and 78. Landline and short-code ranges are rejected:
 * this middleware only ever messages mobiles (OTP, step-up approvals), so a
 * non-mobile number is a registration that could never complete.
 */
@Component
public class ZimbabweMsisdnNormalizer implements MsisdnNormalizer {

    /** +263 7[1|3|7|8] then 7 more digits, e.g. +263771234567. */
    private static final Pattern CANONICAL = Pattern.compile("^\\+2637[1378][0-9]{7}$");

    // Same fail-closed policy as Kenya: letters or stray symbols are a strong
    // signal of user error or malicious input and must not be silently
    // stripped — banking middleware should reject unexpected input outright.
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[0-9+\\s\\-.()]+$");

    @Override
    public Country country() {
        return Country.ZW;
    }

    @Override
    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidMsisdnException("MSISDN is required");
        }
        if (!ALLOWED_CHARS.matcher(input).matches()) {
            throw new InvalidMsisdnException("MSISDN contains characters that are not digits or formatting");
        }

        String digitsOnly = input.replaceAll("[^0-9+]", "");

        String candidate;
        if (digitsOnly.startsWith("+263")) {
            candidate = digitsOnly;
        } else if (digitsOnly.startsWith("263") && digitsOnly.length() == 12) {
            candidate = "+" + digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            candidate = "+263" + digitsOnly.substring(1);
        } else {
            throw new InvalidMsisdnException("MSISDN is not a recognised Zimbabwean format");
        }

        if (!CANONICAL.matcher(candidate).matches()) {
            throw new InvalidMsisdnException("MSISDN is not a valid Zimbabwean mobile number");
        }
        return candidate;
    }

    @Override
    public boolean isValid(String input) {
        try {
            normalize(input);
            return true;
        } catch (InvalidMsisdnException ignored) {
            return false;
        }
    }
}
