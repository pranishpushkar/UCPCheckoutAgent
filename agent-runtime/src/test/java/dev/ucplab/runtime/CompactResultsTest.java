package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.VariantOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Catalog results stay compact for the model (6.2): every model call resends the conversation under one token limit
 * (6.3), so merchant text is cut before it is wrapped and an item carries at most four options.
 */
class CompactResultsTest {

    private static final String ITEM = "gid://shopify/ProductVariant/1000001";

    /** A shoe with a long title, seller and description, and six long options. */
    private static CatalogProduct shoe() {
        List<VariantOption> options = IntStream.range(0, 6)
                .mapToObj(i -> new VariantOption(i + "N".repeat(99), i + "L".repeat(99))).toList();
        return new CatalogProduct("gid://shopify/p/FAKEUPID000001", "T".repeat(300), Optional.of("D".repeat(2000)),
                List.of(new CatalogVariant(ITEM, "T".repeat(300), new Money(499_900, "INR"),
                        new Availability(true, Optional.empty()), Optional.of("S".repeat(200)), options)));
    }

    private static ToolRegistry registry() {
        CatalogGateway catalog = new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                return new CallOutcome.Ok<>(200, new CatalogPage(List.of(shoe()), false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                return new CallOutcome.Ok<>(200, shoe(), List.of());
            }
        };
        return ToolRegistry.of(Stubs.ports(catalog, new Stubs.RecordingOrchestrator()), (q, t) -> Optional.empty(),
                Duration.ofMinutes(5));
    }

    private static Map<String, Object> run(String tool, Map<String, Object> arguments) {
        String json = registry().find(tool).orElseThrow()
                .run(new ToolContext(Stubs.ALICE, new RunId("r")), arguments).json();
        return CanonicalJson.readObject(json);
    }

    /** The text inside one {@code <untrusted ...>} wrapper. */
    private static String inner(Object wrapped) {
        String s = (String) wrapped;
        return s.substring(s.indexOf('>') + 1, s.lastIndexOf("</untrusted>"));
    }

    // Covers: 6.2 compact, 6.3, 6.4 (a search item: title cut to 120, seller to 60, at most four options, the
    // merchant's first four, each name and label cut to 40; still wrapped as untrusted text)
    @Test
    void aSearchItem_isCutForTheModel() {
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) run("search_catalog",
                Map.of("query", "shoes", "max_results", 10)).get("items")).getFirst();

        assertThat(inner(item.get("title"))).isEqualTo("T".repeat(120));
        assertThat(inner(item.get("seller"))).isEqualTo("S".repeat(60));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) item.get("options");
        assertThat(options).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(inner(options.get(i).get("name"))).isEqualTo((i + "N".repeat(99)).substring(0, 40));
            assertThat(inner(options.get(i).get("label"))).isEqualTo((i + "L".repeat(99)).substring(0, 40));
        }
        assertThat((String) item.get("title")).startsWith("<untrusted source=\"merchant\" field=\"title\">");
    }

    // Covers: 6.2 compact (get_product: the description cut to 400, the title and options as in a search)
    @Test
    void getProduct_cutsTheDescription() {
        Map<String, Object> item = run("get_product", Map.of("item_id", ITEM));

        assertThat(inner(item.get("description"))).isEqualTo("D".repeat(400));
        assertThat(inner(item.get("title"))).isEqualTo("T".repeat(120));
        assertThat((List<?>) item.get("options")).hasSize(4);
    }
}
