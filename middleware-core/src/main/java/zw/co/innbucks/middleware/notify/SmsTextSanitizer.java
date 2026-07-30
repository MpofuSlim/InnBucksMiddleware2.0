package zw.co.innbucks.middleware.notify;

import java.text.Normalizer;

/**
 * Makes SMS text safe for the InnBucks notification API / SMS gateway, which
 * rejects non-GSM / non-ASCII characters with {@code 400 "Invalid message"}.
 * Ported verbatim from the ticketing fleet's sanitizer, whose whitelist was
 * established by probing the live gateway one character at a time
 * (2026-07-29): {@code ! : / ? " * ;} are rejected while
 * {@code ( ) - % @ & # ' + . ,} and alphanumerics are accepted.
 *
 * <p>Transliterates typographic punctuation (em/en dashes, curly quotes,
 * ellipsis, non-breaking space, bullet) to ASCII, strips diacritics, then
 * replaces anything still outside the proven-accepted set with a space —
 * never {@code '?'}, which the gateway itself rejects.
 *
 * <p><b>SMS only.</b> Apply on the SMS send path exclusively — email renders
 * Unicode fine and keeps its original typography (only the SUBJECT is
 * charset-validated by the gateway, so {@link NotificationGatewayClient}
 * sanitizes that too).
 */
public final class SmsTextSanitizer {

    private SmsTextSanitizer() {
    }

    /**
     * Returns a GSM/ASCII-safe form of {@code text}. Null/blank pass through
     * unchanged (the callers already reject blank before sending).
     */
    public static String toGsmSafe(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text
                .replace("–", "-").replace("—", "-")   // en dash / em dash
                .replace("‒", "-").replace("―", "-")   // figure dash / horizontal bar
                .replace("‘", "'").replace("’", "'")   // curly single quotes / apostrophe
                .replace("‚", "'").replace("‛", "'")
                // Curly DOUBLE quotes collapse to an apostrophe, not '"' — the
                // gateway rejects the double-quote character outright.
                .replace("“", "'").replace("”", "'")
                .replace("„", "'")
                .replace("…", "...")                        // ellipsis
                .replace(" ", " ")                     // non-breaking space
                // Bullet becomes '-', not '*' — '*' is rejected too.
                .replace("•", "-").replace("·", ".");
        // Strip diacritics (accented -> base letter) so accented copy degrades to
        // GSM-safe ASCII rather than being replaced wholesale by the net below.
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        // The characters the gateway refuses with 400 "Invalid message".
        // Sentence enders become '.', the rest a space so words never run together.
        s = s.replace('!', '.').replace('?', '.').replace(';', '.')
             .replace(':', ' ').replace('/', ' ').replace('*', ' ').replace('"', '\'');
        // Final net: anything outside the proven-accepted set becomes a SPACE.
        // Iterate by CODE POINT so a supplementary character (an emoji, a UTF-16
        // surrogate pair) collapses to one space rather than one per surrogate half.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.append(accepted(cp) ? (char) cp : ' ');
            i += Character.charCount(cp);
        }
        // Collapse the runs of spaces the substitutions above can leave behind.
        return out.toString().replaceAll(" {2,}", " ");
    }

    /**
     * The character set the gateway has been observed to accept. Deliberately a
     * WHITELIST: the rejected set is not documented anywhere, so allowing only
     * what is proven safe is the only way a character we have never tried can't
     * fail a send in production.
     */
    private static boolean accepted(int cp) {
        if (cp == '\n' || cp == '\r' || cp == '\t') {
            return true;
        }
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return " .,()-%@&#'+".indexOf(cp) >= 0;
    }
}
