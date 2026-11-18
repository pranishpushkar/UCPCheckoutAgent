package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** H3 P8: what the client takes from a catalog answer (2026-08-25), field by field, per mutant. This is not a star path. */
class CatalogParserMutationTest {

    private static final String VARIANT = "{\"id\":\"v1\",\"title\":\"Red\",\"price\":{\"amount\":1250,\"currency\":\"USD\"}}";

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String product(String variants) {
        return "{\"id\":\"p1\",\"title\":\"Shirt\",\"variants\":" + variants + "}";
    }

    private static CatalogParser.Parsed<List<CatalogProduct>> lookup(String json) {
        return CatalogParser.parseLookup(bytes(json));
    }

    private static void invalid(String json, String reason) {
        assertThat(lookup(json)).as(json).isEqualTo(new CatalogParser.Parsed.Invalid<List<CatalogProduct>>(reason));
    }

    // Covers: 2.1 (the shapes of an answer: the reason names a field). Kills CatalogParser.read:99 (a body that is not JSON, or not
    // UTF-8, is "unparseable body"; a missing field is named), products:118, product:127, :132 and text:177
    @Test
    void anAnswerThatIsNotWhatTheClientNeeds_isInvalid_andSaysWhy() {
        invalid("not json", "unparseable body");
        assertThat(CatalogParser.parseLookup(new byte[] {(byte) 0xC3, 0x28})).isEqualTo(new CatalogParser.Parsed.Invalid<List<CatalogProduct>>("unparseable body"));
        invalid("{}", "products missing");
        invalid("{\"products\":{}}", "products missing");
        invalid("{\"products\":[\"x\"]}", "product shape");
        invalid("{\"products\":[{\"id\":\"p\",\"title\":\"t\"}]}", "variants missing");
        invalid("{\"products\":[" + product("{}") + "]}", "variants missing");
        invalid("{\"products\":[" + product("[]") + "]}", "variants missing");
        invalid("{\"products\":[" + product("[\"x\"]") + "]}", "variant shape");
        invalid("{\"products\":[{\"title\":\"t\",\"variants\":[" + VARIANT + "]}]}", "id missing");
        invalid("{\"products\":[{\"id\":\" \",\"title\":\"t\",\"variants\":[" + VARIANT + "]}]}", "id missing");
        invalid("{\"products\":[{\"id\":7,\"title\":\"t\",\"variants\":[" + VARIANT + "]}]}", "id missing");
        invalid("{\"products\":[{\"id\":\"p\",\"variants\":[" + VARIANT + "]}]}", "title missing");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"title\":\"t\"}]") + "]}", "price missing");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"title\":\"t\",\"price\":{\"amount\":\"5\",\"currency\":\"USD\"}}]") + "]}", "price.amount not an integer");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"title\":\"t\",\"price\":{\"amount\":5}}]") + "]}", "currency missing");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"title\":\"t\",\"price\":{\"amount\":5,\"currency\":\"EURO\"}}]") + "]}", "price invalid");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"price\":{\"amount\":5,\"currency\":\"USD\"}}]") + "]}", "title missing");
        invalid("{\"products\":[" + product("[{\"title\":\"t\",\"price\":{\"amount\":5,\"currency\":\"USD\"}}]") + "]}", "id missing");
        invalid("{\"products\":[" + product("[{\"id\":\"v\",\"title\":\"t\",\"price\":{\"amount\":5,\"currency\":\"USD\"},\"availability\":\"yes\"}]") + "]}", "availability shape");
    }

    // Covers: 2.1. Kills CatalogParser.availability:167, :170, :171 (availability: absent is not stated; an object gives whether it is
    // available and its status, anything else is refused)
    @Test
    void theAvailability_isUnstated_orWhatTheObjectSays() {
        String base = "{\"id\":\"v\",\"title\":\"t\",\"price\":{\"amount\":5,\"currency\":\"USD\"}";
        assertThat(only(lookup("{\"products\":[" + product("[" + base + "}]") + "]}")).availability()).isEqualTo(Availability.unstated());
        assertThat(only(lookup("{\"products\":[" + product("[" + base + ",\"availability\":{\"available\":true,\"status\":\"in_stock\"}}]") + "]}")).availability())
                .isEqualTo(new Availability(true, Optional.of("in_stock")));
        assertThat(only(lookup("{\"products\":[" + product("[" + base + ",\"availability\":{\"available\":false,\"status\":\" \"}}]") + "]}")).availability())
                .isEqualTo(new Availability(false, Optional.empty()));
        assertThat(only(lookup("{\"products\":[" + product("[" + base + ",\"availability\":{\"available\":\"yes\",\"status\":5}}]") + "]}")).availability())
                .isEqualTo(new Availability(false, Optional.empty()));
        assertThat(only(lookup("{\"products\":[" + product("[" + base + ",\"availability\":{}}]") + "]}")).availability())
                .isEqualTo(new Availability(false, Optional.empty()));
    }

    private static CatalogVariant only(CatalogParser.Parsed<List<CatalogProduct>> parsed) {
        assertThat(parsed).isInstanceOf(CatalogParser.Parsed.Found.class);
        List<CatalogProduct> products = ((CatalogParser.Parsed.Found<List<CatalogProduct>>) parsed).value();
        assertThat(products).hasSize(1);
        assertThat(products.get(0).variants()).hasSize(1);
        return products.get(0).variants().get(0);
    }

    // Covers: 2.1, I8 (a business error is the merchant's own: its code and content, cleaned and cut, and "error" when it names no
    // code). Kills CatalogParser.error:104, :105, :106
    @Test
    void aBusinessError_givesItsCodeAndContent() {
        String head = "{\"ucp\":{\"status\":\"error\"},\"messages\":";
        assertThat(CatalogParser.parseProduct(bytes(head + "[{\"code\":\"not_found\",\"content\":\"no\\nsuch\"}]}")))
                .isEqualTo(new CatalogParser.Parsed.Error<CatalogProduct>("not_found", "no such"));
        assertThat(CatalogParser.parseProduct(bytes(head + "[{\"content\":\"x\"}]}")))
                .isEqualTo(new CatalogParser.Parsed.Error<CatalogProduct>("error", "x"));
        assertThat(CatalogParser.parseProduct(bytes(head + "[{\"code\":\" \",\"content\":5}]}")))
                .isEqualTo(new CatalogParser.Parsed.Error<CatalogProduct>("error", ""));
        assertThat(CatalogParser.parseProduct(bytes(head + "[{\"code\":\"" + "c".repeat(100) + "\",\"content\":\"" + "y".repeat(300) + "\"},{\"code\":\"second\"}]}")))
                .isEqualTo(new CatalogParser.Parsed.Error<CatalogProduct>("c".repeat(64), "y".repeat(200)));
        for (String bad : new String[] {"[]", "{}", "\"x\"", "[\"x\"]"}) {
            assertThat(CatalogParser.parseProduct(bytes(head + bad + "}"))).as(bad)
                    .isEqualTo(new CatalogParser.Parsed.Invalid<CatalogProduct>("error envelope without messages"));
        }
        assertThat(CatalogParser.parseProduct(bytes("{\"ucp\":{\"status\":\"error\"}}")))
                .isEqualTo(new CatalogParser.Parsed.Invalid<CatalogProduct>("error envelope without messages"));
        assertThat(CatalogParser.parseProduct(bytes("{\"ucp\":{\"status\":\"ok\"}}")))
                .isEqualTo(new CatalogParser.Parsed.Invalid<CatalogProduct>("product missing"));
    }

    // Covers: 2.1. A product, a search page and a description are read as the shape says; text is cut and cleaned
    @Test
    void aProductAndAPage_areReadWithTheirDescription() {
        String p = "{\"id\":\"p1\",\"title\":\"" + "T".repeat(300) + "\",\"description\":{\"plain\":\"line1\\nline2\",\"html\":\"<b>x</b>\"},"
                + "\"variants\":[" + VARIANT + "]}";
        CatalogPage page = ((CatalogParser.Parsed.Found<CatalogPage>) CatalogParser.parseSearch(
                bytes("{\"products\":[" + p + "],\"pagination\":{\"has_next_page\":true}}"))).value();
        assertThat(page.hasNextPage()).isTrue();
        assertThat(page.products()).containsExactly(new CatalogProduct("p1", "T".repeat(200), Optional.of("line1 line2"),
                List.of(new CatalogVariant("v1", "Red", new Money(1250, "USD"), Availability.unstated()))));

        for (String pagination : new String[] {"", ",\"pagination\":{}", ",\"pagination\":{\"has_next_page\":false}",
                ",\"pagination\":{\"has_next_page\":\"true\"}", ",\"pagination\":\"x\""}) {
            assertThat(((CatalogParser.Parsed.Found<CatalogPage>) CatalogParser.parseSearch(bytes("{\"products\":[]" + pagination + "}"))).value()
                    .hasNextPage()).as(pagination).isFalse();
        }
        for (String description : new String[] {"", ",\"description\":\"x\"", ",\"description\":{\"markdown\":\"x\"}", ",\"description\":{\"plain\":\" \"}",
                ",\"description\":{\"plain\":5}"}) {
            CatalogProduct one = ((CatalogParser.Parsed.Found<CatalogProduct>) CatalogParser.parseProduct(
                    bytes("{\"product\":{\"id\":\"p\",\"title\":\"t\"" + description + ",\"variants\":[" + VARIANT + "]}}"))).value();
            assertThat(one.description()).as(description).isEmpty();
        }
        assertThat(((CatalogParser.Parsed.Found<CatalogProduct>) CatalogParser.parseProduct(bytes(
                "{\"product\":{\"id\":\"p\",\"title\":\"t\",\"description\":{\"plain\":\"" + "d".repeat(2500) + "\"},\"variants\":[" + VARIANT + "]}}"))).value()
                .description()).contains("d".repeat(2000));
    }
}
