package dev.ucplab.ucp.mcp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Writes back a value {@code LenientJson} read: {@code Map}, {@code List}, {@code String}, {@code Boolean}, {@code Long},
 * {@code BigDecimal} and {@code null}. Used only to hand {@code result.structuredContent} to the REST parsers as a body;
 * the canonical writer stays strict (it refuses {@code null} and decimals, 8.4), so it cannot do this.
 */
final class McpJson {

    private McpJson() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(s, out);
            case Boolean b -> out.append(b);
            case Long l -> out.append(l);
            case BigDecimal d -> out.append(d);
            case Map<?, ?> m -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string((String) e.getKey(), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> l -> {
                out.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(l.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass().getSimpleName());
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
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
}
