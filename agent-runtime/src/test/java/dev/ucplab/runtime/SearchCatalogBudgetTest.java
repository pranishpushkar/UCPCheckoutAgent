package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.Budget;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@code search_catalog} with a run budget (6.2, 23.1, P9): the search carries the budget, set in code from the run, and
 * an item in another currency or priced above it never reaches the model, whatever the merchant returns. Results and
 * {@code get_product} name the variant's own options, wrapped. Built from the Global Catalog fixtures' shape: one variant
 * per product, with its size.
 */
class SearchCatalogBudgetTest {

    private static final Budget BUDGET = new Budget(50_000, "USD");

    private static CatalogProduct board(String n, long minor, String currency, String size) {
        return new CatalogProduct("gid://shopify/p/FAKEUPID00000" + n, "Example Snowboard " + n,
                List.of(new CatalogVariant("gid://shopify/ProductVariant/100000" + n, "Example Snowboard " + n,
                        new Money(minor, currency), new Availability(true, Optional.empty()),
                        Optional.of("Example Shop " + n), List.of(new VariantOption("Size", size)))));
    }

    private static final CatalogProduct IN_BUDGET = board("1", 30_495, "USD", "149 cm");
    private static final CatalogProduct AT_BUDGET = board("2", 50_000, "USD", "152 cm");
    private static final CatalogProduct ABOVE = board("3", 50_001, "USD", "156 cm");
    private static final CatalogProduct OTHER_CURRENCY = board("4", 20_000, "CAD", "160 cm");
    private static final List<CatalogProduct> ALL = List.of(ABOVE, IN_BUDGET, OTHER_CURRENCY, AT_BUDGET);

    private final List<CatalogSearch> asked = new CopyOnWriteArrayList<>();

    private ToolRegistry registry() {
        CatalogGateway recording = new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                asked.add(request);
                return new CallOutcome.Ok<>(200, new CatalogPage(ALL, false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                return new CallOutcome.Ok<>(200, IN_BUDGET, List.of());
            }
        };
        return ToolRegistry.of(Stubs.ports(recording, new Stubs.RecordingOrchestrator()), (q, t) -> Optional.empty(),
                Duration.ofMinutes(5));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(ToolContext context) {
        String json = registry().find("search_catalog").orElseThrow()
                .run(context, Map.of("query", "snowboard", "max_results", 10)).json();
        ResultFields.check(json);
        return (List<Map<String, Object>>) CanonicalJson.readObject(json).get("items");
    }

    // Covers: 6.2, 23.1, P9 (with a budget the search asks for nothing above it, in the budget's currency, from the run
    // and never from the model; an item above it or in another currency is left out, one at the budget is kept)
    @Test
    void withABudget_theSearchCarriesIt_andNothingAboveItReachesTheModel() {
        ToolContext context = new ToolContext(Stubs.CONTEXT.auth(), Stubs.CONTEXT.runId(), Optional.of(BUDGET));

        List<Map<String, Object>> items = items(context);

        assertThat(asked).singleElement().satisfies(s -> assertThat(s.maxPrice()).contains(new Money(50_000, "USD")));
        assertThat(items).extracting(i -> i.get("item_id")).containsExactly("gid://shopify/ProductVariant/1000001",
                "gid://shopify/ProductVariant/1000002");
        assertThat(items).allSatisfy(i -> {
            assertThat(i.get("currency")).isEqualTo("USD");
            assertThat(((Number) i.get("price_minor")).longValue()).isLessThanOrEqualTo(50_000);
        });
    }

    // Covers: 6.2 (without a budget the search carries no price limit and every item is returned)
    @Test
    void withoutABudget_theSearchCarriesNoLimit_andEveryItemIsReturned() {
        List<Map<String, Object>> items = items(Stubs.CONTEXT);

        assertThat(asked).singleElement().satisfies(s -> assertThat(s.maxPrice()).isEmpty());
        assertThat(items).hasSize(4);
    }

    // Covers: 6.2, 26.7, 6.4 (a result names its variant's own options, each name and label wrapped as untrusted text:
    // the variant an item_id buys, since the catalog returns one variant per product)
    @Test
    @SuppressWarnings("unchecked")
    void aResult_andGetProduct_nameTheVariantsOptions_wrapped() {
        Map<String, Object> first = items(Stubs.CONTEXT).stream()
                .filter(i -> i.get("item_id").equals("gid://shopify/ProductVariant/1000001")).findFirst().orElseThrow();
        String product = registry().find("get_product").orElseThrow()
                .run(Stubs.CONTEXT, Map.of("item_id", "gid://shopify/ProductVariant/1000001")).json();
        ResultFields.check(product);

        for (Map<String, Object> item : List.of(first, CanonicalJson.readObject(product))) {
            assertThat((List<Map<String, Object>>) item.get("options")).singleElement().satisfies(o -> {
                assertThat((String) o.get("name")).startsWith("<untrusted ").contains("Size").endsWith("</untrusted>");
                assertThat((String) o.get("label")).startsWith("<untrusted ").contains("149 cm")
                        .endsWith("</untrusted>");
            });
        }
    }
}
