package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogParserTest {

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String product(String id, String title, String variantExtra) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"description\":{\"plain\":\"x\"},"
                + "\"price_range\":{\"min\":{\"amount\":1200,\"currency\":\"USD\"},\"max\":{\"amount\":1200,\"currency\":\"USD\"}},"
                + "\"variants\":[{\"id\":\"" + id + "\",\"title\":\"" + title + "\","
                + "\"price\":{\"amount\":1200,\"currency\":\"USD\"}" + variantExtra + "}]}";
    }

    private static CatalogPage page(CatalogParser.Parsed<CatalogPage> parsed) {
        assertThat(parsed).isInstanceOf(CatalogParser.Parsed.Found.class);
        return ((CatalogParser.Parsed.Found<CatalogPage>) parsed).value();
    }

    private static String invalidReason(CatalogParser.Parsed<?> parsed) {
        assertThat(parsed).isInstanceOf(CatalogParser.Parsed.Invalid.class);
        return ((CatalogParser.Parsed.Invalid<?>) parsed).reason();
    }

    // Covers: FR1 (price is an integer in minor units; availability is the merchant's word)
    @Test
    void parseSearch_readsProductsPricesAndAvailability() {
        String body = "{\"ucp\":{\"version\":\"2026-08-25\"},\"products\":["
                + product("item_456", "Blue Mug", ",\"availability\":{\"available\":true,\"status\":\"in_stock\"}") + ","
                + product("item_789", "Sneakers", ",\"availability\":{\"available\":false,\"status\":\"out_of_stock\"}") + "],"
                + "\"pagination\":{\"has_next_page\":true,\"cursor\":\"2\"}}";

        CatalogPage page = page(CatalogParser.parseSearch(bytes(body)));

        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.products()).extracting(CatalogProduct::id).containsExactly("item_456", "item_789");
        assertThat(page.products().get(0).variants().get(0).price().minor()).isEqualTo(1200);
        assertThat(page.products().get(0).variants().get(0).availability().available()).isTrue();
        assertThat(page.products().get(1).variants().get(0).availability().status()).contains("out_of_stock");
    }

    // Covers: FR1 (a merchant that does not state availability is not trusted to have the item)
    @Test
    void parseSearch_variantWithoutAvailability_isNotAvailable() {
        CatalogPage page = page(CatalogParser.parseSearch(bytes("{\"products\":[" + product("a", "A", "") + "]}")));

        assertThat(page.products().get(0).variants().get(0).availability().available()).isFalse();
        assertThat(page.hasNextPage()).isFalse();
    }

    // Covers: 8.4 (unknown fields, nulls and decimals in fields we do not use are ignored)
    @Test
    void parseSearch_ignoresUnknownFieldsNullsAndDecimals() {
        String body = "{\"products\":[{\"id\":\"a\",\"title\":\"A\",\"rating\":{\"value\":4.5},\"handle\":null,"
                + "\"variants\":[{\"id\":\"a\",\"title\":\"A\",\"unit_price\":{\"amount\":1.5},\"seller\":null,"
                + "\"price\":{\"amount\":100,\"currency\":\"EUR\"}}]}],\"messages\":null}";

        assertThat(page(CatalogParser.parseSearch(bytes(body))).products()).hasSize(1);
    }

    // Covers: 8.4 (a field we use must have the right type, or the body is invalid)
    @Test
    void parseSearch_invalidFields_areInvalid() {
        for (String variant : List.of(
                "\"price\":{\"amount\":12.5,\"currency\":\"USD\"}",
                "\"price\":{\"amount\":\"1200\",\"currency\":\"USD\"}",
                "\"price\":{\"amount\":-1,\"currency\":\"USD\"}",
                "\"price\":{\"amount\":100,\"currency\":\"dollars\"}",
                "\"price\":{\"amount\":100}",
                "\"cost\":100")) {
            String body = "{\"products\":[{\"id\":\"a\",\"title\":\"A\",\"variants\":[{\"id\":\"a\",\"title\":\"A\"," + variant + "}]}]}";
            assertThat(invalidReason(CatalogParser.parseSearch(bytes(body)))).as(variant).isNotBlank();
        }
        assertThat(invalidReason(CatalogParser.parseSearch(bytes("{\"products\":[{\"id\":\"a\",\"title\":\"A\",\"variants\":[]}]}"))))
                .isEqualTo("variants missing");
        assertThat(invalidReason(CatalogParser.parseSearch(bytes("{\"items\":[]}")))).isEqualTo("products missing");
        assertThat(invalidReason(CatalogParser.parseSearch(bytes("{\"products\":[{\"title\":\"A\",\"variants\":[]}]}")))).isNotBlank();
    }

    // Covers: 8.1 (a body that is not JSON, not UTF-8 or not an object is invalid, and the reason never repeats it)
    @Test
    void parseSearch_garbage_isInvalid_withoutEchoingTheBody() {
        for (byte[] body : List.of(bytes("<html>oops secret-token</html>"), bytes("[1,2]"), bytes("{\"products\":"),
                new byte[] {(byte) 0xC3, (byte) 0x28}, new byte[0])) {
            assertThat(invalidReason(CatalogParser.parseSearch(body))).doesNotContain("secret-token");
        }
    }

    // Covers: I8-adjacent (merchant text reaches the model and the logs: control characters are replaced, length is cut)
    @Test
    void parseSearch_cleansAndCutsMerchantText() {
        String longTitle = "x".repeat(500);
        String body = "{\"products\":[" + product("a", "Bad\\nTitle\\u0007", "") + ","
                + product("b", longTitle, ",\"availability\":{\"available\":true,\"status\":\"in\\nstock\"}") + "]}";

        CatalogPage page = page(CatalogParser.parseSearch(bytes(body)));

        assertThat(page.products().get(0).title()).isEqualTo("Bad Title ");
        assertThat(page.products().get(1).title()).hasSize(200);
        assertThat(page.products().get(1).variants().get(0).availability().status()).contains("in stock");
    }

    // Covers: FR1, 8.1 (an unknown product is a business error envelope, not a body to retry)
    @Test
    void parseProduct_errorEnvelope_isAnError() {
        String body = "{\"ucp\":{\"version\":\"2026-08-25\",\"status\":\"error\"},\"messages\":[{\"type\":\"error\","
                + "\"code\":\"not_found\",\"content\":\"Product not found: x\",\"severity\":\"unrecoverable\"}]}";

        CatalogParser.Parsed<CatalogProduct> parsed = CatalogParser.parseProduct(bytes(body));

        assertThat(parsed).isEqualTo(new CatalogParser.Parsed.Error<CatalogProduct>("not_found", "Product not found: x"));
    }

    // Covers: FR1 (a product response carries one product object)
    @Test
    void parseProduct_readsTheProduct_andNeedsOne() {
        CatalogParser.Parsed<CatalogProduct> ok = CatalogParser.parseProduct(bytes("{\"product\":" + product("a", "A", "") + "}"));
        assertThat(((CatalogParser.Parsed.Found<CatalogProduct>) ok).value().id()).isEqualTo("a");
        assertThat(invalidReason(CatalogParser.parseProduct(bytes("{\"products\":[]}")))).isEqualTo("product missing");
    }

    // Covers: FR1 (a lookup that found nothing is an empty list, not an error)
    @Test
    void parseLookup_emptyProducts_isEmpty() {
        CatalogParser.Parsed<List<CatalogProduct>> parsed = CatalogParser.parseLookup(bytes(
                "{\"products\":[],\"messages\":[{\"type\":\"info\",\"code\":\"not_found\",\"content\":\"x\"}]}"));

        assertThat(parsed).isEqualTo(new CatalogParser.Parsed.Found<>(List.<CatalogProduct>of()));
    }

    // Covers: FR1, 6.2 get_product (the 2026-08-25 description is an object keyed by format; its plain text is read)
    @Test
    void parseSearch_plainDescription_isRead() {
        CatalogPage page = page(CatalogParser.parseSearch(bytes("{\"products\":[" + product("a", "A", "") + "]}")));

        assertThat(page.products().get(0).description()).contains("x");
    }

    // Covers: FR1, 8.4 (a missing description, another shape, or a format other than plain reads as none; the
    // product still parses)
    @Test
    void parseSearch_descriptionMissingOrOtherShape_readsAsNone() {
        String base = product("a", "A", "");
        for (String replacement : List.of("", "\"description\":\"flat\",", "\"description\":{\"html\":\"<b>x</b>\"},",
                "\"description\":{\"plain\":\"  \"},", "\"description\":{\"plain\":7},", "\"description\":null,")) {
            String body = "{\"products\":[" + base.replace("\"description\":{\"plain\":\"x\"},", replacement) + "]}";

            CatalogPage page = page(CatalogParser.parseSearch(bytes(body)));

            assertThat(page.products().get(0).description()).as(replacement).isEmpty();
        }
    }

    // Covers: 6.4, I8 (a description is merchant text: control characters are replaced and it is cut to a fixed length)
    @Test
    void parseSearch_description_isCleanedAndBounded() {
        String body = "{\"products\":[" + product("a", "A", "")
                .replace("{\"plain\":\"x\"}", "{\"plain\":\"one\\u0007two" + "y".repeat(5000) + "\"}") + "]}";

        String description = page(CatalogParser.parseSearch(bytes(body))).products().get(0).description()
                .orElseThrow();

        assertThat(description).startsWith("one two").hasSize(2000).doesNotContain("\u0007");
    }
}
