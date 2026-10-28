package zw.co.innbucks.middleware.kyc.nationalid;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kenya national ID number: digits only. Kept deliberately loose — 5 to 9
 * digits, no checksum, leading zeros kept as typed — because the number has
 * no published check digit and its length has grown over the decades;
 * refusing a real old card would lock its holder out, while a mistyped one
 * still meets a reviewer with the card's photo. Tighten only on the market's
 * confirmed rules.
 */
@Component
@ConditionalOnKycEnabled
public class KenyaNationalIdValidator implements NationalIdValidator {

    private static final Pattern CANONICAL = Pattern.compile("^[0-9]{5,9}$");

    @Override
    public Country country() {
        return Country.KE;
    }

    @Override
    public String canonicalise(String input) {
        String candidate = NationalIds.stripSeparatorsAndUpperCase(input);
        if (!CANONICAL.matcher(candidate).matches()) {
            throw new InvalidNationalIdException("a Kenyan national ID is 5 to 9 digits");
        }
        return candidate;
    }

    /** Digits only: there is no other common way to write it. */
    @Override
    public Set<String> commonSpellings(String canonical) {
        return Set.of(canonical);
    }
}
