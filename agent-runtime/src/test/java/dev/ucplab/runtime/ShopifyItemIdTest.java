package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Shopify GIDs as item ids (8.1, I6, I8; H5 C1). */
class ShopifyItemIdTest {

    private static final String VARIANT = "gid://shopify/ProductVariant/51192065753174";
    private static final String ITEM_ID = "item_id must be 1 to 100 of letters, digits, '_' and '-', "
            + "or gid://shopify/<Type>/<digits>";

    private final Stubs.RecordingOrchestrator orchestrator = new Stubs.RecordingOrchestrator();
    private final List<String> lookups = new CopyOnWriteArrayList<>();

    private ToolRegistry registry(List<dev.ucplab.core.merchant.CatalogProduct> products) {
        return ToolRegistry.of(Stubs.ports(Stubs.catalog(products, id -> {
            lookups.add(id);
            return Stubs.notFound();
        }), orchestrator), (q, t) -> Optional.empty(), Duration.ofMinutes(5));
    }

    private ToolOutcome run(String tool, Map<String, Object> args) {
        return registry(List.of()).find(tool).orElseThrow().run(Stubs.CONTEXT, args);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> propose(Object itemId) {
        return args("merchant_id", "shop-1", "items", List.of(args("item_id", itemId, "quantity", 1)),
                "rationale", "r");
    }

    private static void assertInvalid(ToolOutcome outcome, String detail) {
        assertThat(outcome.code()).isEqualTo("invalid_argument");
        assertThat(CanonicalJson.readObject(outcome.json()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("error", "invalid_argument", "detail", detail));
    }

    // Covers: 6.2, 8.1, H5 C1 (an item id may be a Shopify GID in its exact form)
    @Test
    void shopifyVariantGid_isAnItemId() {
        assertThat(run("get_product", args("item_id", VARIANT)).code()).isEqualTo("not_found");
        assertThat(lookups).containsExactly(VARIANT);

        assertThat(run("propose_purchase", propose(VARIANT)).code()).isEqualTo("ok");
        assertThat(orchestrator.calls.getFirst()).contains("itemId=" + VARIANT + ",");
    }

    // Covers: 6.2, I6, I8, H5 C1 (everything refused before stays refused, a GID with extra parts included; a checkout
    // id with ?key= is never an item id)
    @Test
    void notExactlyAGid_isRefused() {
        for (String bad : List.of(
                VARIANT + "\n",
                "gid://shopify/ProductVariant/1\nx",
                "gid://shopify/ProductVariant/" + "1".repeat(300),
                "x".repeat(300),
                VARIANT + "?key=0123abcd",
                "gid://shopify/Checkout/hWN1x2?key=0123abcd",
                "gid://shopify/Cart/hWN1x2?cart=abc",
                "gid://shopify/ProductVariant/../1",
                "gid://shopify/../ProductVariant/1",
                "gid://shopify/ProductVariant/abc",
                "gid://shopify/ProductVariant/",
                "gid://shopify//1",
                "gid://other/ProductVariant/1",
                "GID://shopify/ProductVariant/1",
                "https://shopify/ProductVariant/1",
                "gid://shopify/ProductVariant/1#frag",
                "gid://shopify/Product Variant/1")) {
            assertInvalid(run("get_product", args("item_id", bad)), ITEM_ID);
            assertInvalid(run("propose_purchase", propose(bad)), ITEM_ID);
        }
        assertThat(lookups).isEmpty();
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: 6.2, H5 C1 (only an item id may be a GID: merchant and intent ids keep their rule)
    @Test
    void gid_isNotAMerchantOrIntentId() {
        String id = "must be 1 to 100 of letters, digits, '_' and '-'";
        assertInvalid(run("propose_purchase", args("merchant_id", VARIANT, "items",
                List.of(args("item_id", "item_1", "quantity", 1)), "rationale", "r")), "merchant_id " + id);
        assertInvalid(run("get_purchase_status", args("intent_id", VARIANT)), "intent_id " + id);
    }

    // Covers: 6.2, H5 C1 (the item_id pattern the model is given is the rule the tools apply)
    @Test
    void toolSchemas_giveTheModelTheSameItemIdRule() {
        ToolRegistry tools = registry(List.of());
        String json = CanonicalJson.write(Map.of("p", Args.ITEM_ID_SCHEMA));
        String quoted = json.substring(5, json.length() - 1);
        for (String tool : List.of("get_product", "propose_purchase")) {
            String schema = tools.find(tool).orElseThrow().spec().parametersJsonSchema();
            assertThat(schema).contains("\"pattern\":" + quoted);
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(Args.ITEM_ID_SCHEMA);
        assertThat(pattern.matcher(VARIANT).find()).isTrue();
        assertThat(pattern.matcher("item_456").find()).isTrue();
        assertThat(pattern.matcher(VARIANT + "?key=0123abcd").find()).isFalse();
    }

    // Covers: 6.2, I6, H5 C1 (search shows a GID variant as it is, and drops a variant whose id carries a key)
    @Test
    void search_showsGidVariants_andDropsKeyedIds() {
        ToolOutcome outcome = registry(List.of(
                Stubs.product(VARIANT, "Snowboard", "A board", 69995),
                Stubs.product("gid://shopify/Checkout/hWN1x2?key=0123abcd", "Keyed", "k", 1)))
                .find("search_catalog").orElseThrow()
                .run(Stubs.CONTEXT, args("query", "board", "max_results", 5));

        assertThat(outcome.code()).isEqualTo("ok");
        assertThat(outcome.json()).contains("\"item_id\":\"" + VARIANT + "\"").doesNotContain("?key=")
                .doesNotContain("hWN1x2");
    }
}
