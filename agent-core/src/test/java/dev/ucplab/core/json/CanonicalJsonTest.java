package dev.ucplab.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.PropertySettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    /** Key fragments: mixed case, non-ASCII, BMP characters above the surrogates, emoji, and shared prefixes. */
    private static final String[] KEY_PIECES = {
        "a", "A", "ab", "aB", "b", "B", "_", "1", "\u00e9", "\u00df", "\u03a9", "\u4e2d", "\uE000", "\uFB01", "\uFFFD",
        "\uD83D\uDE00", "\uD83D\uDC4D", "\uD83C\uDDF3\uD83C\uDDF1"};
    private static final int MAP_TRIES = 1_000;
    private static final int MAX_KEYS = 12;
    private static final int MAX_PIECES_PER_KEY = 3;

    // Covers: 8.4 (keys sorted by UTF-16 code units, no extra whitespace), nested objects and arrays
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
    // Seeded loop (14.1): -Dproperty.seed and -Dproperty.tries apply; a failure names the seed that replays it.
    @Test
    void canonicalJson_isIndependentOfInsertionOrder() {
        PropertySettings settings = PropertySettings.fromSystemProperties(MAP_TRIES);
        if (settings.randomSeed()) {
            System.out.println("[property] canonical JSON: property.seed=random chose seed " + settings.seed());
        }
        long seed = settings.seed();
        for (int i = 0; i < settings.tries(); i++, seed = PropertySettings.next(seed)) {
            Map<String, Integer> content = randomMap(new SplittableRandom(seed));
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(content.entrySet());
            Map<String, Integer> forward = new LinkedHashMap<>();
            entries.forEach(e -> forward.put(e.getKey(), e.getValue()));
            Collections.reverse(entries);
            Map<String, Integer> backward = new LinkedHashMap<>();
            entries.forEach(e -> backward.put(e.getKey(), e.getValue()));

            assertThat(CanonicalJson.write(forward))
                    .as("map %d of %d, seed %d (replay: %s): %s", i + 1, settings.tries(), seed,
                            PropertySettings.replay(seed), content)
                    .isEqualTo(CanonicalJson.write(backward));
        }
    }

    private static Map<String, Integer> randomMap(SplittableRandom random) {
        Map<String, Integer> m = new LinkedHashMap<>();
        int keys = random.nextInt(MAX_KEYS + 1);
        for (int k = 0; k < keys; k++) {
            StringBuilder key = new StringBuilder();
            int pieces = 1 + random.nextInt(MAX_PIECES_PER_KEY);
            for (int p = 0; p < pieces; p++) {
                key.append(KEY_PIECES[random.nextInt(KEY_PIECES.length)]);
            }
            m.put(key.toString(), random.nextInt());
        }
        return m;
    }

    // Covers: 8.4, RFC 8785 (keys sorted by UTF-16 code units, which differs from code point order above the BMP
    // surrogates: U+1F600 is D83D DE00 in UTF-16, so it sorts before U+E000 and U+FB01 although its code point is
    // larger. Code point order would be z, U+E000, U+FB01, U+1F600)
    @Test
    void canonicalJson_ordersKeysByUtf16CodeUnitsNotCodePoints_always() {
        Map<String, Object> m = new HashMap<>();
        m.put("\uFB01", 1);       // U+FB01 LATIN SMALL LIGATURE FI: code unit FB01
        m.put("\uD83D\uDE00", 2); // U+1F600 GRINNING FACE: code units D83D DE00
        m.put("\uE000", 3);       // U+E000 private use: code unit E000
        m.put("z", 4);            // U+007A: code unit 007A

        assertThat(CanonicalJson.write(m))
                .isEqualTo("{\"z\":4,\"\uD83D\uDE00\":2,\"\uE000\":3,\"\uFB01\":1}");
    }

    // Covers: 7.4, I7 (a frozen body read back and rewritten is byte-identical)
    @Test
    void read_roundTripsWrittenJson_byteForByte() {
        String json = "{\"a\":[1,-2,true,false],\"b\":{\"c\":\"x\\\"y\\\\z\\n\\u0001\"},\"d\":9007199254740993}";

        Object value = CanonicalJson.read(json);

        assertThat(CanonicalJson.write(value)).isEqualTo(json);
        assertThat(CanonicalJson.readObject(json).get("d")).isEqualTo(9007199254740993L);
    }

    // Covers: 8.4 (whitespace between tokens is accepted on read)
    @Test
    void read_acceptsWhitespace_betweenTokens() {
        assertThat(CanonicalJson.read(" { \"k\" : [ 1 , 2 ] } ")).isEqualTo(Map.of("k", List.of(1L, 2L)));
    }

    // Covers: 8.4 (fail closed: null, fractions, duplicates and malformed input are refused)
    @Test
    void read_throws_forUnsupportedOrMalformedInput() {
        for (String bad : List.of("null", "1.5", "1e3", "{\"a\":1,\"a\":2}", "[1,]", "{\"a\"}", "\"x", "[1] 2", "")) {
            assertThatThrownBy(() -> CanonicalJson.read(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CanonicalJson.readObject("[1]")).isInstanceOf(IllegalArgumentException.class);
    }
}
