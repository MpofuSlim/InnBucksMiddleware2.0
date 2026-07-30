package zw.co.innbucks.middleware.common.msisdn;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;

import java.util.regex.Pattern;

@Component
public class KenyaMsisdnNormalizer implements MsisdnNormalizer {

    // Safaricom + Airtel + Telkom Kenya mobile prefixes (post-7-or-1).
    private static final Pattern CANONICAL = Pattern.compile("^\\+254(7|1)[0-9]{8}$");

    // Reject anything containing characters other than digits, +, and common
    // separators (space, dash, dot, parens). Letters or stray symbols are a
    // strong signal of user error or malicious input and must not be silently
    // stripped — banking middleware should fail closed on unexpected input.
    private static final Pattern ALLOWED_CHARS = Pattern.compile("^[0-9+\\s\\-.()]+$");

    @Override
    public Country country() {
        return Country.KE;
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
        if (digitsOnly.startsWith("+254")) {
            candidate = digitsOnly;
        } else if (digitsOnly.startsWith("254") && digitsOnly.length() == 12) {
            candidate = "+" + digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            candidate = "+254" + digitsOnly.substring(1);
        } else {
            throw new InvalidMsisdnException("MSISDN is not a recognised Kenyan format");
        }

        if (!CANONICAL.matcher(candidate).matches()) {
            throw new InvalidMsisdnException("MSISDN is not a valid Kenyan mobile number");
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
