package dev.ucplab.core.json;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical JSON writer: object keys sorted by UTF-16 code units, no whitespace (8.4). Used for
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
