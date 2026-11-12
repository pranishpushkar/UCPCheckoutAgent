package dev.ucplab.faultproxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A small strict JSON reader and writer, because the proxy depends on the JDK only (5.2, §12).
 *
 * <p>{@link #read} gives {@code Map<String, Object>} (insertion order, duplicate keys refused), {@code List},
 * {@code String}, {@code Long} (integers without fraction or exponent), {@code Double} (every other number, finite only),
 * {@code Boolean} and {@link #NULL}. Trailing characters, nesting deeper than {@link #MAX_DEPTH}, control characters in
 * strings and malformed escapes are refused. {@link #write} accepts the same value set, plus {@code Integer}.
 */
public final class Json {

    /** The JSON {@code null} value. Java {@code null} is never produced or accepted. */
    public enum Null { NULL }

    public static final Null NULL = Null.NULL;

    static final int MAX_DEPTH = 32;

    private Json() {
    }

    /** @throws IllegalArgumentException on malformed input */
    public static Object read(String json) {
        Objects.requireNonNull(json, "json");
        Reader r = new Reader(json);
        Object value = r.value(0);
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

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        switch (value) {
            case null -> throw new IllegalArgumentException("null is not a JSON value; use Json.NULL");
            case Null n -> out.append("null");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b);
            case Long l -> out.append(l);
            case Integer i -> out.append(i);
            case Double d -> {
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("not a finite number: " + d);
                }
                out.append(d);
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("object keys must be strings");
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(key, out);
                    out.append(':');
                    writeValue(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting deeper than " + MAX_DEPTH);
            }
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                Object v = value(depth + 1);
                if (map.putIfAbsent(key, v) != null) {
                    throw error("duplicate key \"" + key + "\"");
                }
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return Collections.unmodifiableMap(map);
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                list.add(value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
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
                char e = next();
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
                            throw error("short \\u escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos++), 16);
                            if (d < 0) {
                                throw error("bad \\u escape");
                            }
                            code = code * 16 + d;
                        }
                        out.append((char) code);
                    }
                    default -> throw error("bad escape '\\" + e + "'");
                }
            }
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                while (isDigit(peek())) {
                    pos++;
                }
            } else {
                throw error("bad number");
            }
            boolean integer = true;
            if (peek() == '.') {
                integer = false;
                pos++;
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                integer = false;
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                digits();
            }
            String text = s.substring(start, pos);
            if (integer) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    throw error("integer out of range");
                }
            }
            double d = Double.parseDouble(text);
            if (!Double.isFinite(d)) {
                throw error("number out of range");
            }
            return d;
        }

        private void digits() {
            if (!isDigit(peek())) {
                throw error("bad number");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("unexpected token");
            }
            pos += word.length();
            return value;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
        }
    }
}
