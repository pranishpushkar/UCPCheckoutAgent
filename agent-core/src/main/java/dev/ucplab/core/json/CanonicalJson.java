package dev.ucplab.core.json;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical JSON writer: object keys sorted by UTF-16 code units, as {@link String#compareTo} does and RFC 8785
 * specifies (not by code point, which orders characters above U+FFFF differently), no whitespace (8.4). Used for
 * frozen request bodies (7.4, I7) and the approval snapshot hash (9.2).
 *
 * <p>Accepted values: {@code Map} with {@code String} keys, {@code List}, {@code String},
 * {@code Boolean}, and the integer types {@code Long}, {@code Integer}, {@code Short} and
 * {@code Byte}. Everything else is refused, including {@code null} and floating point: amounts
 * are integer minor units, and an absent value is expressed by leaving the key out.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    /**
     * Parses JSON written by {@link #write} (or any JSON within the same value set) into {@code Map} (sorted keys),
     * {@code List}, {@code String}, {@code Long} and {@code Boolean}. {@code null} and non-integer numbers are refused.
     *
     * @throws IllegalArgumentException on malformed input or an unsupported value
     */
    public static Object read(String json) {
        Objects.requireNonNull(json, "json");
        Reader r = new Reader(json);
        Object value = r.value();
        r.skipWhitespace();
        if (r.pos != json.length()) {
            throw r.error("trailing characters");
        }
        return value;
    }

    /** {@link #read} for a document whose top level must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String json) {
        if (!(read(json) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> throw error("null is not allowed in canonical JSON");
                default -> number();
            };
        }

        private Map<String, Object> object() {
            pos++;
            TreeMap<String, Object> m = new TreeMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                if (m.put(key, value()) != null) {
                    throw error("duplicate key " + key);
                }
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect('}');
                    return m;
                }
            }
        }

        private List<Object> array() {
            pos++;
            List<Object> list = new java.util.ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return List.copyOf(list);
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else {
                    expect(']');
                    return List.copyOf(list);
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("short unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }

        private Long number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos < s.length() && ".eE".indexOf(s.charAt(pos)) >= 0) {
                throw error("only integers are allowed");
            }
            String digits = s.substring(start, pos);
            if (digits.isEmpty() || digits.equals("-")) {
                throw error("unexpected character");
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException ex) {
                throw error("integer out of range");
            }
        }

        private Boolean literal(String word, Boolean value) {
            if (!s.startsWith(word, pos)) {
                throw error("unexpected character");
            }
            pos += word.length();
            return value;
        }

        private char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        void skipWhitespace() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("invalid JSON at " + pos + ": " + what);
        }
    }

    private static void writeValue(Object value, StringBuilder out) {
        switch (value) {
            case null -> throw new IllegalArgumentException("null is not allowed in canonical JSON");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b.booleanValue());
            case Long n -> out.append(n.longValue());
            case Integer n -> out.append(n.intValue());
            case Short n -> out.append(n.shortValue());
            case Byte n -> out.append(n.byteValue());
            case Map<?, ?> map -> writeObject(map, out);
            case List<?> list -> writeArray(list, out);
            default -> throw new IllegalArgumentException(
                    "unsupported type in canonical JSON: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        // Natural String order: UTF-16 code units (8.4, RFC 8785).
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings: " + e.getKey());
            }
            sorted.put(key, e.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(e.getKey(), out);
            out.append(':');
            writeValue(e.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeValue(list.get(i), out);
        }
        out.append(']');
    }

    private static void writeString(String s, StringBuilder out) {
        Objects.requireNonNull(s, "s");
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                            throw new IllegalArgumentException("unpaired surrogate at index " + i);
                        }
                        out.append(c).append(s.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("unpaired surrogate at index " + i);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
