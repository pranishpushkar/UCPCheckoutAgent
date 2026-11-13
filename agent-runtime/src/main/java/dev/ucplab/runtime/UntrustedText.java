package dev.ucplab.runtime;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merchant or user text on its way to the model (6.4, 9.3 L2). The tools apply it to every merchant string (titles,
 * descriptions) and to the user's {@code ask_user} answers; our own fields (ids, numbers, states, reason codes) are not
 * wrapped. The model client never applies it. This lowers the risk of prompt injection; I2 and I3 are the real defence.
 */
public final class UntrustedText {

    /** The most code points of text kept; longer text is cut and marked with {@link #TRUNCATED}. */
    public static final int MAX_CODE_POINTS = 500;

    public static final String TRUNCATED = "…[truncated]";

    private static final Pattern ATTRIBUTE = Pattern.compile("[a-z][a-z_]{0,31}");
    private static final Pattern DELIMITER = Pattern.compile("<(/?untrusted)", Pattern.CASE_INSENSITIVE);

    private UntrustedText() {
    }

    /**
     * In order: removes every code point of category Cc (control) and Cf (format: bidi controls, zero-width characters);
     * collapses runs of whitespace to one space and strips both ends; truncates to {@value #MAX_CODE_POINTS} code points
     * (never splitting a surrogate pair), marking the cut; turns any {@code <untrusted} or {@code </untrusted}, in any
     * case, into {@code &lt;untrusted} or {@code &lt;/untrusted} so the text cannot close the wrapper; and wraps the
     * result as {@code <untrusted source="..." field="...">...</untrusted>}.
     *
     * @param source who wrote the text, for example {@code merchant} or {@code user}: our own constant
     * @param field which field it is, for example {@code description}: our own constant
     * @throws IllegalArgumentException if {@code source} or {@code field} is not 1 to 32 of {@code [a-z_]}, starting
     *     with a letter
     */
    public static String wrap(String source, String field, String text) {
        checkAttribute("source", source);
        checkAttribute("field", field);
        Objects.requireNonNull(text, "text");
        String kept = truncate(collapseWhitespace(removeControlAndFormat(text)));
        String neutral = DELIMITER.matcher(kept).replaceAll(m -> "&lt;" + Matcher.quoteReplacement(m.group(1)));
        return "<untrusted source=\"" + source + "\" field=\"" + field + "\">" + neutral + "</untrusted>";
    }

    private static void checkAttribute(String name, String value) {
        Objects.requireNonNull(value, name);
        if (!ATTRIBUTE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 1 to 32 of [a-z_], starting with a letter");
        }
    }

    private static String removeControlAndFormat(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints()
                .filter(cp -> Character.getType(cp) != Character.CONTROL && Character.getType(cp) != Character.FORMAT)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String collapseWhitespace(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean pending = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pending = !out.isEmpty();
            } else {
                if (pending) {
                    out.append(' ');
                    pending = false;
                }
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    private static String truncate(String s) {
        if (s.codePointCount(0, s.length()) <= MAX_CODE_POINTS) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, MAX_CODE_POINTS)) + TRUNCATED;
    }
}
