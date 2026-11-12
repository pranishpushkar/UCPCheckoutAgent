package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class JsonTest {

    // Covers: §12 (rule set JSON is read by the proxy itself, JDK only)
    @Test
    void read_parsesEveryValueKind() {
        Object v = Json.read(" { \"a\" : [1, -2, 0.5, 1e3, true, false, null], \"b\": {\"c\": \"x\"} } ");

        assertThat(v).isEqualTo(Map.of(
                "a", List.of(1L, -2L, 0.5, 1000.0, true, false, Json.NULL),
                "b", Map.of("c", "x")));
    }

    // Covers: §12 (escapes, including \\u and the solidus)
    @Test
    void read_decodesEscapes() {
        assertThat(Json.read("\"q\\\"b\\\\s\\/n\\nr\\rt\\tb\\bf\\fu\\u00e9\\u0041\""))
                .isEqualTo("q\"b\\s/n\nr\rt\tb\bf\fuéA");
    }

    // Covers: §12 (a malformed rule set is refused, never half-read)
    @ParameterizedTest
    @ValueSource(strings = {
            "", "{", "{\"a\":1", "{\"a\":1}x", "{\"a\":1} {}", "[1,]", "{\"a\":1,}", "{a:1}", "'x'", "01", "-", "1.",
            "1e", ".5", "+1", "\"\\x\"", "\"\\u12\"", "\"\\u12g4\"", "\"unterminated", "tru", "nul",
            "{\"a\":1,\"a\":2}", "99999999999999999999", "1e999", "\"tab\there\"", "NaN"})
    void read_refusesMalformedInput(String json) {
        assertThatThrownBy(() -> Json.read(json)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: §12 (nesting depth is bounded, so a hostile body cannot exhaust the stack)
    @Test
    void read_refusesNestingDeeperThanTheLimit() {
        String ok = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);

        assertThat(Json.read(ok)).isInstanceOf(List.class);
        assertThatThrownBy(() -> Json.read(deep)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nesting");
    }

    // Covers: §12 (readObject refuses a non-object top level)
    @Test
    void readObject_refusesAnArray() {
        assertThatThrownBy(() -> Json.readObject("[]")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: §12 (the log is written as JSON; what is written reads back the same)
    @Test
    void write_roundTripsThroughRead() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("s", "quote\" back\\ nl\n ctl\u0001 é");
        value.put("n", 42L);
        value.put("i", 7);
        value.put("d", 0.25);
        value.put("b", true);
        value.put("z", Json.NULL);
        value.put("l", List.of(1L, "two"));

        String json = Json.write(value);

        assertThat(json).startsWith("{\"s\":");
        Map<String, Object> back = Json.readObject(json);
        assertThat(back.get("s")).isEqualTo(value.get("s"));
        assertThat(back.get("i")).isEqualTo(7L);
        assertThat(back.get("z")).isEqualTo(Json.NULL);
        assertThat(back.get("l")).isEqualTo(List.of(1L, "two"));
    }

    // Covers: §12 (Java null and non-finite numbers are never written)
    @Test
    void write_refusesNullAndNonFiniteNumbers() {
        assertThatThrownBy(() -> Json.write(java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
