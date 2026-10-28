package zw.co.innbucks.middleware.kyc.nationalid;

/**
 * Country-neutral pieces of national-ID handling: the separator stripping
 * every validator starts from, and the display mask.
 */
public final class NationalIds {

    /** Longest input looked at; anything longer is not an ID number. */
    static final int MAX_INPUT_LENGTH = 40;

    private static final char MASK = '\u2022';

    private NationalIds() {
    }

    /**
     * Upper-cases ASCII letters and drops spaces and dashes of every common
     * flavour (the hyphen-minus, Unicode hyphens and dashes, the minus sign,
     * no-break and other Unicode spaces). Any OTHER character refuses: letters
     * outside ASCII must not be folded into ASCII (the dotless i upper-cases
     * to a plain I), and a stray symbol is a typo or an attack, not
     * formatting.
     */
    public static String stripSeparatorsAndUpperCase(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidNationalIdException("the national ID is required");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new InvalidNationalIdException("the national ID is too long");
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (c >= 'a' && c <= 'z') {
                out.append((char) (c - ('a' - 'A')));
            } else if (isSeparator(c)) {
                // formatting: dropped
            } else {
                throw new InvalidNationalIdException(
                        "the national ID may contain only letters, digits, spaces and dashes");
            }
        }
        if (out.isEmpty()) {
            throw new InvalidNationalIdException("the national ID is required");
        }
        return out.toString();
    }

    static boolean isSeparator(char c) {
        return switch (c) {
            // spaces: ASCII, tab, no-break, figure, narrow no-break, ideographic
            case ' ', '\t', '\u00A0', '\u2007', '\u202F', '\u3000' -> true;
            // dashes: hyphen-minus, hyphen, non-breaking hyphen, figure dash,
            // en dash, em dash, horizontal bar, minus sign, small and
            // full-width hyphen-minus
            case '-', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212', '\uFE63', '\uFF0D' -> true;
            default -> c >= '\u2000' && c <= '\u200A';
        };
    }

    /**
     * The display form returned to the app: the first two and last three
     * characters with bullets between ({@code 63•••••••X18}, the shape the app asked for). For a
     * short number that would leave fewer than five characters hidden (a
     * 7-8 digit Kenyan ID shows most of itself that way), only the last three
     * are shown.
     */
    public static String mask(String canonical) {
        int n = canonical.length();
        if (n <= 3) {
            return String.valueOf(MASK).repeat(n);
        }
        if (n - 5 >= 5) {
            return canonical.substring(0, 2) + String.valueOf(MASK).repeat(n - 5) + canonical.substring(n - 3);
        }
        return String.valueOf(MASK).repeat(n - 3) + canonical.substring(n - 3);
    }
}
