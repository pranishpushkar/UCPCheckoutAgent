package dev.ucplab.service.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The second layer of I8 (section 11, m6-plan section 10): masks what looks like a credential in a log message or an
 * exception text. The first layer is that our own types redact themselves and no body is logged; this catches what a
 * library or a careless line might still write.
 */
public final class Redaction {

    public static final String MASK = "[redacted]";

    private record Rule(Pattern pattern, String replacement) {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;
    /** An optional backslash before a quote, for a body that was escaped into a JSON string first. */
    private static final String Q = "\\\\?\"";

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(Authorization\\s*[:=]\\s*)[^\\r\\n]*", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("(Bearer\\s+)[^\\s\"',;]+", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("((?:Set-)?Cookie\\s*[:=]\\s*)[^\\r\\n]*", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("(ucplab_session\\s*=\\s*)[^;\\s\"',]+", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("(X-CSRF-Token\\s*[:=]\\s*)[^\\s\"',;]+", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("(x-goog-api-key\\s*[:=]\\s*)[^\\s\"',;]+", FLAGS), "$1" + MASK),
            new Rule(Pattern.compile("\\btok_[A-Za-z0-9_\\-]+"), MASK),
            new Rule(Pattern.compile("(" + Q + "(?:credential|token)" + Q + "\\s*:\\s*" + Q + ")[^\"\\\\]*(" + Q + ")",
                    FLAGS), "$1" + MASK + "$2"));

    private Redaction() {
    }

    /** {@code text} with every credential-shaped part masked; {@code null} stays {@code null}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }
}
