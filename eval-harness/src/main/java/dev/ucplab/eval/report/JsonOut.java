package dev.ucplab.eval.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * A small pretty JSON writer for the report: maps keep their insertion order, two-space indent, doubles rounded to
 * four places (rates and bounds need no more), {@link #NULL} for JSON null.
 */
final class JsonOut {

    /** JSON null. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    private JsonOut() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (value == NULL) {
            sb.append("null");
        } else if (value instanceof String s) {
            string(sb, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Double d) {
            sb.append(number(d));
        } else if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(sb, depth + 1);
                string(sb, (String) e.getKey());
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List<?> l) {
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            if (l.stream().allMatch(x -> x instanceof String || x instanceof Number)) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    write(sb, l.get(i), depth);
                    if (i + 1 < l.size()) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                indent(sb, depth + 1);
                write(sb, l.get(i), depth + 1);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    static String number(double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("not a finite number: " + d);
        }
        BigDecimal b = BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        String s = b.scale() <= 0 ? b.setScale(1, RoundingMode.UNNECESSARY).toPlainString() : b.toPlainString();
        return s;
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
