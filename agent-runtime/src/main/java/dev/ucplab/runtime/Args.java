package dev.ucplab.runtime;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads tool arguments, which are model output. A bad argument throws {@link Invalid} with our fixed wording: it names
 * at most a field we defined, and never echoes model text (an unknown argument is not named either).
 */
final class Args {

    /** An item, intent or merchant id as a tool accepts it: 1 to 100 of {@code [A-Za-z0-9_-]}. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,100}");

    private Args() {
    }

    /** A bad argument; carries only our fixed detail. No stack trace: it is control flow inside the tools. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String detail) {
            super(detail, null, false, false);
        }
    }

    /** Refuses any argument not in {@code allowed}, among them any user, email or owner the model sends (T1). */
    static void only(Map<String, Object> args, Set<String> allowed) {
        for (String name : args.keySet()) {
            if (!allowed.contains(name)) {
                throw new Invalid("unknown argument");
            }
        }
    }

    /**
     * A string with Cc and Cf code points removed and the ends stripped, of {@code min} to {@code max} code points.
     */
    static String text(Map<String, Object> args, String name, int min, int max) {
        String detail = name + " must be a string of " + min + " to " + max + " characters";
        if (!(args.get(name) instanceof String s)) {
            throw new Invalid(detail);
        }
        String clean = stripControl(s);
        int length = clean.codePointCount(0, clean.length());
        if (length < min || length > max) {
            throw new Invalid(detail);
        }
        return clean;
    }

    /** A string with Cc and Cf removed and the ends stripped, cut to {@code max} code points. */
    static String truncated(Map<String, Object> args, String name, int max) {
        if (!(args.get(name) instanceof String s)) {
            throw new Invalid(name + " must be a string");
        }
        String clean = stripControl(s);
        return clean.codePointCount(0, clean.length()) <= max ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, max));
    }

    static String id(Map<String, Object> args, String name) {
        return id(args.get(name), name);
    }

    static String id(Object value, String name) {
        if (value instanceof String s && ID.matcher(s).matches()) {
            return s;
        }
        throw new Invalid(name + " must be 1 to 100 of letters, digits, '_' and '-'");
    }

    /** A whole number from {@code min} to {@code max}; a JSON number with a fraction is refused. */
    static int integer(Object value, String name, int min, int max) {
        String detail = name + " must be a whole number from " + min + " to " + max;
        long n;
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            n = ((Number) value).longValue();
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal d = value instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) value).doubleValue());
            try {
                n = d.stripTrailingZeros().longValueExact();
            } catch (ArithmeticException e) {
                throw new Invalid(detail);
            }
        } else {
            throw new Invalid(detail);
        }
        if (n < min || n > max) {
            throw new Invalid(detail);
        }
        return (int) n;
    }

    static List<?> list(Map<String, Object> args, String name, int min, int max) {
        if (args.get(name) instanceof List<?> l && l.size() >= min && l.size() <= max) {
            return l;
        }
        throw new Invalid(name + " must be a list of " + min + " to " + max + " entries");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) {
        if (value instanceof Map<?, ?> m && m.keySet().stream().allMatch(k -> k instanceof String)) {
            return (Map<String, Object>) m;
        }
        throw new Invalid(name + " must be an object");
    }

    private static String stripControl(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints()
                .filter(cp -> Character.getType(cp) != Character.CONTROL && Character.getType(cp) != Character.FORMAT)
                .forEach(out::appendCodePoint);
        return out.toString().strip();
    }
}
