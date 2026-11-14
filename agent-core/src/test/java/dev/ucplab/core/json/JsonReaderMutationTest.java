package dev.ucplab.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reader and writer edges that the H3 P5 mutation pass found unchecked (8.4, 7.4, I7, 9.2): every body we hash,
 * send or store goes through this reader, so a malformed document must be refused with an {@code IllegalArgumentException}
 * (never another exception, never a half-read value) and a well-formed one must read exactly. Both readers are driven:
 * strict ({@link CanonicalJson#read}) and lenient ({@link LenientJson}, which keeps null, decimals and big integers).
 */
class JsonReaderMutationTest {

    private static Object strict(String json) {
        return CanonicalJson.read(json);
    }

    private static Object lenient(String json) {
        CanonicalJson.Reader r = new CanonicalJson.Reader(json, true);
        Object value = r.value();
        r.skipWhitespace();
        r.requireEnd();
        return value;
    }

    private static void assertRefused(String json) {
        assertThatThrownBy(() -> strict(json)).as("strict: " + json).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRefusedLenient(String json) {
        assertThatThrownBy(() -> lenient(json)).as("lenient: " + json).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- structure and whitespace

    // Covers: 8.4. Kills CanonicalJson$Reader.object:94, :95, :96, :100 (skipWhitespace, the empty-object test, the step
    // past "}") and array:124 (skipWhitespace): whitespace is allowed around every token, and an empty object or array
    // reads empty whether it is the whole document or nested, with its neighbours intact
    @Test
    void whitespaceAndEmptyContainers_readExactly() {
        assertThat(strict("{}")).isEqualTo(Map.of());
        assertThat(strict("  { }  ")).isEqualTo(Map.of());
        assertThat(strict("[ ]")).isEqualTo(List.of());
        assertThat(strict("{\"a\":{},\"b\":[],\"c\":1}")).isEqualTo(Map.of("a", Map.of(), "b", List.of(), "c", 1L));
        assertThat(strict(" { \"a\" : [ 1 , 2 ] , \"b\" : { \"c\" : true } } "))
                .isEqualTo(Map.of("a", List.of(1L, 2L), "b", Map.of("c", true)));
        assertThat(LenientJson.readObject("{ \"a\" : 1 }  \n")).isEqualTo(Map.of("a", 1L));
    }

    // Covers: 8.4, 9.2. Kills CanonicalJson$Reader.object:97 EmptyObjectReturnVals (the documented result of read is a Map
    // with sorted keys: an object read from a document, empty or not, is a SortedMap in key order, which the snapshot hash
    // and the frozen bodies rely on when they are read back and written again)
    @Test
    void objects_areReadAsSortedMaps_emptyOrNot() {
        assertThat(strict("{}")).isInstanceOf(java.util.SortedMap.class);
        assertThat(strict("{\"b\":1,\"a\":{}}")).isInstanceOf(java.util.SortedMap.class);
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) strict("{\"b\":1,\"a\":{}}")).keySet())).isEqualTo(List.of("a", "b"));
        assertThat(((Map<?, ?>) strict("{\"b\":1,\"a\":{}}")).get("a")).isInstanceOf(java.util.SortedMap.class);
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.expect:261 RemoveConditional_EQUAL_ELSE and object:101 (a missing colon, a
    // missing comma, a key that is not a string, an unclosed container): refused
    @Test
    void missingPunctuation_isRefused() {
        for (String json : List.of("{\"a\" 1}", "{\"a\":1 \"b\":2}", "{1:2}", "{\"a\":1", "{\"a\":1,}", "[1 2]", "[1,", "[1",
                "{\"a\":1]", "[1}", "{", "[", "{\"a\"", "{\"a\":", "")) {
            assertRefused(json);
        }
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.literal:246 RemoveConditional_EQUAL_ELSE: a word that only starts like
    // true, false or null is refused
    @Test
    void misspeltLiterals_areRefused() {
        for (String json : List.of("tru", "trux", "fals", "falsy", "[nul]", "{\"a\":nulx}")) {
            assertRefusedLenient(json);
        }
        assertThat(lenient("[true,false,null]")).isEqualTo(java.util.Arrays.asList(true, false, null));
        assertRefused("null");
    }

    // ---------------------------------------------------------------- numbers

    // Covers: 8.4. Kills CanonicalJson$Reader.number:190, :193, :197, :219, parseIntegral:233 and peek:254: integers read
    // as Long (the sign, zero, the largest and smallest long); an integer out of range is refused by the strict reader and
    // is a BigDecimal for the lenient one; a lone sign or a character that is no value is refused
    @Test
    void integers_readAsLong_andOutOfRangeFollowsTheReader() {
        assertThat(strict("0")).isEqualTo(0L);
        assertThat(strict("-7")).isEqualTo(-7L);
        assertThat(strict("12")).isEqualTo(12L);
        assertThat(strict("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(strict("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(strict("[1,-2]")).isEqualTo(List.of(1L, -2L));
        assertRefused("9223372036854775808");
        assertThat(lenient("9223372036854775808")).isEqualTo(new BigDecimal("9223372036854775808"));
        for (String json : List.of("-", "x", "[-]", "{\"a\":x}", "+1")) {
            assertRefused(json);
        }
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.number:197 and :208 (the strict reader refuses a decimal point or an exponent
    // with the same refusal): only integers, never a rounded amount
    @Test
    void strictReader_refusesDecimalsAndExponents() {
        for (String json : List.of("1.5", "1e5", "1E5", "1.", "[1.5]", "{\"a\":2.0}")) {
            assertRefused(json);
        }
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.number:204, :208, :210, :211, :213 (every branch of the lenient decimal): the
    // point, the digits after it, e and E, the sign of the exponent (+ and -), and the digits of the exponent
    @Test
    void lenientReader_readsDecimalsAndExponents() {
        assertThat(lenient("1.5")).isEqualTo(new BigDecimal("1.5"));
        assertThat(lenient("-1.25")).isEqualTo(new BigDecimal("-1.25"));
        assertThat(lenient("1e3")).isEqualTo(new BigDecimal("1e3"));
        assertThat(lenient("1E3")).isEqualTo(new BigDecimal("1E3"));
        assertThat(lenient("1e+3")).isEqualTo(new BigDecimal("1e+3"));
        assertThat(lenient("1e-3")).isEqualTo(new BigDecimal("1e-3"));
        assertThat(lenient("1.5e2")).isEqualTo(new BigDecimal("1.5e2"));
        assertThat(lenient("[1.5,2e1]")).isEqualTo(List.of(new BigDecimal("1.5"), new BigDecimal("2e1")));
        assertThat(lenient("{\"a\":12}")).isEqualTo(Map.of("a", 12L));
        assertRefusedLenient("1e");
        assertRefusedLenient("1e+");
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.number:193, :197, :204, :208, :210, :213 ConditionalsBoundary and
    // RemoveConditional_ORDER_IF and peek:254 (a document cut off inside a number, at every point, is an
    // IllegalArgumentException and never an index error): the readers never read past the end
    @Test
    void aDocumentCutOffInsideANumber_isRefusedWithTheRightException() {
        for (String json : List.of("{\"a\":1", "{\"a\":-", "{\"a\":1.", "{\"a\":1.5", "{\"a\":1e", "{\"a\":1e+", "{\"a\":1e+5",
                "[1", "[1.5")) {
            assertRefusedLenient(json);
        }
        for (String json : List.of("{\"a\":1", "{\"a\":-", "[1")) {
            assertRefused(json);
        }
    }

    // ---------------------------------------------------------------- strings

    // Covers: 8.4. Kills CanonicalJson$Reader.string:152 (a raw control character in a string is refused; a space and the
    // escaped forms are read): the control characters below 0x20 never pass
    @Test
    void controlCharactersInAString_areRefused_andSpacesAndEscapesAreRead() {
        assertRefused("\"a\nb\"");
        assertRefused("\"a\tb\"");
        assertRefused("\"a\u0001b\"");
        assertThat(strict("\"a b\"")).isEqualTo("a b");
        assertThat(strict("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"")).isEqualTo("\" \\ / \b \f \n \r \t");
        assertThat(strict("\"\\u0041\\u00e9\"")).isEqualTo("A\u00e9");
    }

    // Covers: 8.4. Kills CanonicalJson$Reader.string:159 ConditionalsBoundary / ORDER_ELSE and :173 MathMutator /
    // ORDER_ELSE (an escape cut off, a short or non-hex unicode escape, an unknown escape, an unterminated string): all
    // IllegalArgumentException, never an index error
    @Test
    void brokenStringsAndEscapes_areRefused() {
        for (String json : List.of("\"abc", "\"abc\\", "\"\\u12", "\"\\u12\"", "\"\\uZZZZ\"", "\"\\x\"", "\"\\u0041")) {
            assertRefused(json);
        }
    }

    // ---------------------------------------------------------------- writer

    // Covers: 8.4, 7.4, I7. Kills CanonicalJson.writeString:351 RemoveConditional_EQUAL_ELSE and :354 Increments (a
    // surrogate pair is written whole, once; a lone high or low surrogate, or a high one followed by another character,
    // is refused, never written as a broken string)
    @Test
    void writeString_writesSurrogatePairsWhole_andRefusesLoneSurrogates() {
        String smile = "\uD83D\uDE00";

        assertThat(CanonicalJson.write("a" + smile + "b")).isEqualTo("\"a" + smile + "b\"");
        assertThat(CanonicalJson.write(smile + smile)).isEqualTo("\"" + smile + smile + "\"");
        for (String lone : List.of("\uD83D", "\uD83Dx", "\uDE00", "x\uDE00", "\uD83D\uD83D")) {
            assertThatThrownBy(() -> CanonicalJson.write(lone)).as(lone).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unpaired surrogate");
        }
    }
}
