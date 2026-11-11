package dev.ucplab.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LenientJsonTest {

    // Covers: 8.4 (responses are read leniently: null is allowed and kept as a Java null)
    @Test
    void readObject_allowsNull_inObjectsAndArrays() {
        Map<String, Object> m = LenientJson.readObject("{\"a\":null,\"b\":[1,null,\"x\"]}");

        assertThat(m).containsEntry("a", null).containsKey("a");
        assertThat(m.get("b")).isEqualTo(java.util.Arrays.asList(1L, null, "x"));
    }

    // Covers: 8.4 (decimals and exponents are allowed and read as BigDecimal; integers stay Long)
    @Test
    void readObject_readsDecimalsAsBigDecimal_andIntegersAsLong() {
        Map<String, Object> m = LenientJson.readObject(
                "{\"i\":-42,\"d\":54.00,\"e\":1.5e3,\"big\":123456789012345678901234567890}");

        assertThat(m.get("i")).isEqualTo(-42L);
        assertThat(m.get("d")).isEqualTo(new BigDecimal("54.00"));
        assertThat(m.get("e")).isEqualTo(new BigDecimal("1.5e3"));
        assertThat(m.get("big")).isEqualTo(new BigDecimal("123456789012345678901234567890"));
    }

    // Covers: 8.4 (unknown fields of any shape are kept, nested)
    @Test
    void readObject_keepsUnknownNestedValues() {
        Map<String, Object> m = LenientJson.readObject("{\"x\":{\"y\":[true,false,{\"z\":\"\\u00e9\\n\"}]}}");

        assertThat(m.toString()).contains("true").contains("false").contains("é");
    }

    // Covers: 8.4 (still JSON: duplicate keys, trailing text and a top-level array are refused)
    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{\"a\":1} x", "{\"a\":", "{\"a\":01x}", "{\"a\":nul}", "{\"a\":-}", ""})
    void readObject_refusesMalformedJson(String json) {
        assertThatThrownBy(() -> LenientJson.readObject(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readObject_refusesNonObjectTopLevel() {
        assertThatThrownBy(() -> LenientJson.readObject("[1]")).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 7.4, 9.2 (the strict reader and writer used for hashes are unchanged: null and decimals stay refused)
    @Test
    void canonicalJson_staysStrict() {
        assertThatThrownBy(() -> CanonicalJson.read("{\"a\":null}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.read("{\"a\":1.5}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(java.util.Collections.singletonMap("a", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
