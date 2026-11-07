package dev.ucplab.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    // Covers: 8.4 (sorted keys, no extra whitespace), nested objects and arrays
    @Test
    void canonicalJson_sortsKeysAndOmitsWhitespace_forNestedValues() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("quantity", 2);
        line.put("itemId", "item_123");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("totalMinor", 5400L);
        root.put("currency", "USD");
        root.put("lines", List.of(line));
        root.put("approved", true);

        assertThat(CanonicalJson.write(root)).isEqualTo(
                "{\"approved\":true,\"currency\":\"USD\",\"lines\":[{\"itemId\":\"item_123\",\"quantity\":2}],"
                        + "\"totalMinor\":5400}");
    }

    // Covers: 8.4 (keys sorted by UTF-16 code units: uppercase before lowercase, shorter prefix first)
    @Test
    void canonicalJson_ordersKeysByCodeUnit_always() {
        Map<String, Object> m = new HashMap<>();
        m.put("b", 1);
        m.put("B", 2);
        m.put("ab", 3);
        m.put("a", 4);

        assertThat(CanonicalJson.write(m)).isEqualTo("{\"B\":2,\"a\":4,\"ab\":3,\"b\":1}");
    }

    // Covers: 8.4 (RFC 8259 escaping; non-ASCII emitted as is)
    @Test
    void canonicalJson_escapesControlCharsQuotesAndBackslash_andKeepsUnicode() {
        String s = "q\"b\\s/\b\f\n\r\t\u0001\u001f é 😀";

        assertThat(CanonicalJson.write(s))
                .isEqualTo("\"q\\\"b\\\\s/\\b\\f\\n\\r\\t\\u0001\\u001f é 😀\"");
    }

    // Covers: 8.4 (amounts are integers), 2.1 (money in integer minor units)
    @Test
    void canonicalJson_writesIntegerTypes_asPlainDigits() {
        assertThat(CanonicalJson.write(List.of(Long.MAX_VALUE, -1, (short) 7, (byte) 0)))
                .isEqualTo("[9223372036854775807,-1,7,0]");
    }

    // Covers: 8.4 (fail closed on values that have no single canonical form)
    @Test
    void canonicalJson_throws_forUnsupportedValues() {
        Map<Object, Object> nonStringKey = new HashMap<>();
        nonStringKey.put(1, "x");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> CanonicalJson.write(1.5d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(new BigDecimal("1.0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(withNull)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(nonStringKey)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write("\uD800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write("\uDC00x")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: I7, 8.4 (the same content always gives byte-identical output, whatever the map's insertion order)
    @Property(tries = 1000)
    void canonicalJson_isIndependentOfInsertionOrder(@ForAll Map<String, Integer> content) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(content.entrySet());
        Map<String, Integer> forward = new LinkedHashMap<>();
        entries.forEach(e -> forward.put(e.getKey(), e.getValue()));
        Collections.reverse(entries);
        Map<String, Integer> backward = new LinkedHashMap<>();
        entries.forEach(e -> backward.put(e.getKey(), e.getValue()));

        assertThat(CanonicalJson.write(forward)).isEqualTo(CanonicalJson.write(backward));
    }
}
