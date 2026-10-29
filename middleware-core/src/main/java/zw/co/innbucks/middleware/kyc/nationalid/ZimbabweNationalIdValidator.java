package zw.co.innbucks.middleware.kyc.nationalid;

import org.springframework.stereotype.Component;
import zw.co.innbucks.middleware.common.country.Country;
import zw.co.innbucks.middleware.kyc.ConditionalOnKycEnabled;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Zimbabwe national registration number, as printed on the card:
 * {@code 63-1234567 X 18}.
 *
 * <ul>
 *   <li>2 digits — the district where the person was registered;</li>
 *   <li>6 or 7 digits — the registration serial;</li>
 *   <li>1 letter — a check letter;</li>
 *   <li>2 digits — the district of origin.</li>
 * </ul>
 *
 * <p>Canonical form: those four parts run together, upper case —
 * {@code 631234567X18} (11 or 12 characters).
 *
 * <p><b>Structure only, deliberately.</b> How the check letter is computed is
 * not published by the Registrar General, and the third-party libraries that
 * validate ZW IDs check the shape alone; a guessed algorithm that turned out
 * wrong would refuse real customers with no way round it. District codes are
 * not checked against a list either: {@code 00} (no district of origin) was
 * announced as abolished in 2021, but cards carrying it remain valid. The
 * reviewer compares the number with the photo of the card; this check exists
 * to catch typing slips (a missing digit, a letter where a digit goes)
 * before the customer is told the ID is wrong a day later.
 */
@Component
@ConditionalOnKycEnabled
public class ZimbabweNationalIdValidator implements NationalIdValidator {

    private static final Pattern CANONICAL = Pattern.compile("^[0-9]{2}[0-9]{6,7}[A-Z][0-9]{2}$");

    /** Separators people put between the four parts. */
    private static final List<String> SEPARATORS = List.of("", "-", " ");

    @Override
    public Country country() {
        return Country.ZW;
    }

    @Override
    public String canonicalise(String input) {
        String candidate = NationalIds.stripSeparatorsAndUpperCase(input);
        if (!CANONICAL.matcher(candidate).matches()) {
            throw new InvalidNationalIdException("a Zimbabwean national ID is 2 digits, 6 or 7 digits, "
                    + "a letter and 2 digits, e.g. 63-1234567 X 18");
        }
        return candidate;
    }

    /**
     * Every combination of "", "-" and " " at the three part boundaries, with
     * the check letter in either case: {@code 63-1234567 X 18},
     * {@code 63-1234567-x-18}, {@code 631234567x18}, … — 54 spellings. The
     * sign-up form took the number as typed, so these are what its hashes
     * were taken over.
     */
    @Override
    public Set<String> commonSpellings(String canonical) {
        if (!CANONICAL.matcher(canonical).matches()) {
            return Set.of(canonical);
        }
        int n = canonical.length();
        String district = canonical.substring(0, 2);
        String serial = canonical.substring(2, n - 3);
        String letter = canonical.substring(n - 3, n - 2);
        String origin = canonical.substring(n - 2);
        Set<String> out = new LinkedHashSet<>();
        out.add(canonical);
        for (String l : List.of(letter, letter.toLowerCase(Locale.ROOT))) {
            for (String a : SEPARATORS) {
                for (String b : SEPARATORS) {
                    for (String c : SEPARATORS) {
                        out.add(district + a + serial + b + l + c + origin);
                    }
                }
            }
        }
        return out;
    }
}
