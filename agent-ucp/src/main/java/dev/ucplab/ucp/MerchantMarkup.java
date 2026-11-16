package dev.ucplab.ucp;

import java.util.Locale;

/**
 * Removes markup from merchant product text before it is kept (6.4, 9.3 L2), in this order: a {@code <script>} or
 * {@code <style>} element with its content; an HTML comment whole, an unclosed {@code <!--} taking the rest of the
 * text; every other tag replaced by a space; then, once, the six entities {@code &amp; &lt; &gt; &quot; &#39; &nbsp;}.
 * Decoding comes last, so a decoded {@code <} or {@code >} is plain text and is never read as markup. A {@code <} that
 * starts no tag (one not followed by a letter, {@code /}, {@code !} or {@code ?}, or never closed by {@code >}) is text.
 * The result has no leading or trailing whitespace. Pure.
 *
 * <p>This removes text a buyer would not see; it does not stop prompt injection. Visible text still reaches the model
 * as untrusted data ({@code UntrustedText}), and I6, I2 and I3 are the defence.
 */
final class MerchantMarkup {

    private static final String[] RAW_TEXT = {"script", "style"};
    private static final String[][] ENTITIES = {
            {"&amp;", "&"}, {"&lt;", "<"}, {"&gt;", ">"}, {"&quot;", "\""}, {"&#39;", "'"}, {"&nbsp;", " "}};

    private MerchantMarkup() {
    }

    static String strip(String s) {
        return decode(removeMarkup(s)).strip();
    }

    private static String removeMarkup(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
            } else if (lower.startsWith("<!--", i)) {
                int end = lower.indexOf("-->", i + 4);
                if (end < 0) {
                    break;
                }
                i = end + 3;
            } else if (rawText(lower, i) instanceof String name) {
                int close = lower.indexOf("</" + name, i + 1 + name.length());
                int end = close < 0 ? -1 : lower.indexOf('>', close);
                if (end < 0) {
                    break;
                }
                out.append(' ');
                i = end + 1;
            } else if (startsTag(s, i) && s.indexOf('>', i + 1) >= 0) {
                out.append(' ');
                i = s.indexOf('>', i + 1) + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The raw-text element whose start tag begins at {@code i}, or null. */
    private static String rawText(String lower, int i) {
        for (String name : RAW_TEXT) {
            int after = i + 1 + name.length();
            if (lower.startsWith(name, i + 1)
                    && (after == lower.length() || !Character.isLetterOrDigit(lower.charAt(after)))) {
                return name;
            }
        }
        return null;
    }

    private static boolean startsTag(String s, int i) {
        if (i + 1 >= s.length()) {
            return false;
        }
        char next = s.charAt(i + 1);
        return Character.isLetter(next) || next == '/' || next == '!' || next == '?';
    }

    private static String decode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        outer:
        while (i < s.length()) {
            if (s.charAt(i) == '&') {
                for (String[] e : ENTITIES) {
                    if (s.startsWith(e[0], i)) {
                        out.append(e[1]);
                        i += e[0].length();
                        continue outer;
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }
}
