package dev.ucplab.merchant;

import java.util.*;

/**
 * Minimal JSON reader/writer so the merchant core has zero dependencies.
 * Objects are Map, arrays are List, numbers are Long (integers) or Double.
 * Swap for Jackson when the Spring Boot adapter is added.
 */
final class Json {
    private Json() {}

    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != text.length()) throw new IllegalArgumentException("Unexpected trailing characters at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, false);
        return sb.toString();
    }

    /** Same as write, but object keys are sorted: used to fingerprint request bodies. */
    static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean sorted) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Double d) {
            sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(m.entrySet());
            if (sorted) entries.sort(Comparator.comparing((Map.Entry<?, ?> e) -> String.valueOf(e.getKey())));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : entries) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), sorted);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, sorted);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + v.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object readValue() {
            if (pos >= s.length()) throw new IllegalArgumentException("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("Invalid literal at " + pos);
            pos += word.length();
            return value;
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') throw new IllegalArgumentException("Expected string key at " + pos);
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("Expected ':' at " + pos);
                pos++;
                skipWs();
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("Unterminated object");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == '}') return m;
                throw new IllegalArgumentException("Expected ',' or '}' at " + (pos - 1));
            }
        }

        List<Object> readArray() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(readValue());
                skipWs();
                if (pos >= s.length()) throw new IllegalArgumentException("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == ']') return l;
                throw new IllegalArgumentException("Expected ',' or ']' at " + (pos - 1));
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= s.length()) throw new IllegalArgumentException("Bad escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape \\" + e);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalArgumentException("Unexpected character '" + s.charAt(pos) + "' at " + pos);
            String t = s.substring(start, pos);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.valueOf(t);
            return Long.valueOf(t);
        }
    }
}
