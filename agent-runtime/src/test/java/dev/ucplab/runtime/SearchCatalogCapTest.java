package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.Transport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 6.2 at most 5 from a catalog source (26.7): enforced in code, whatever {@code max_results} the model asks for. */
class SearchCatalogCapTest {

    private static final MerchantId CATALOG = new MerchantId("shopify-catalog");
    private static final MerchantId STORE = new MerchantId("store");
    private static final String NOTE = "the catalog gives at most 5 results per search";

    /** A merchant that answers whatever limit it is asked with ten items, and records the limits it was asked for. */
    private static CatalogGateway ten(String prefix, List<Integer> asked) {
        List<CatalogProduct> products = IntStream.range(0, 10).mapToObj(i -> new CatalogProduct(prefix + i, "Shoe " + i,
                Optional.empty(), List.of(new CatalogVariant(prefix + "-v" + i, "Shoe " + i, new Money(1_000_000, "INR"),
                        new Availability(true, Optional.empty()), Optional.of("Seller"), List.of())))).toList();
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                asked.add(request.limit());
                return new CallOutcome.Ok<>(200, new CatalogPage(products, false), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final MerchantEntry CATALOG_SOURCE = new MerchantEntry(CATALOG,
            URI.create("https://catalog.shopify.com/api/ucp/mcp"), "Shopify catalog", MerchantStatus.ACTIVE,
            Transport.CATALOG, Completion.LINK, false, Optional.empty());
    private static final MerchantEntry A_STORE = new MerchantEntry(STORE, URI.create("http://127.0.0.1:1"), "Store",
            MerchantStatus.ACTIVE);

    private static Map<String, Object> search(List<MerchantEntry> active, Map<MerchantId, CatalogGateway> catalogs,
                                              long max) {
        ToolRegistry tools = ToolRegistry.of(new ToolPorts(Stubs.registry(active), catalogs,
                new Stubs.RecordingOrchestrator()), (q, t) -> Optional.empty(), Duration.ofMinutes(5));
        String json = tools.find("search_catalog").orElseThrow().run(new ToolContext(Stubs.ALICE, new RunId("r"),
                Optional.empty(), new BuyerCountry("IN")), Map.of("query", "shoes", "max_results", max)).json();
        return CanonicalJson.readObject(json);
    }

    private static List<?> items(Map<String, Object> result) {
        return (List<?>) result.get("items");
    }

    // Covers: 6.2 at most 5 from a catalog source, 26.7 (a request for 10 at a catalog source returns 5, asks it for 5,
    // and says so in the result, our own fixed text)
    @Test
    void aRequestForTen_atACatalogSource_returnsFive_withTheNote() {
        List<Integer> asked = new CopyOnWriteArrayList<>();
        Map<String, Object> result = search(List.of(CATALOG_SOURCE), Map.of(CATALOG, ten("c", asked)), 10);

        assertThat(items(result)).hasSize(5);
        assertThat(asked).containsExactly(5);
        assertThat(result.get("note")).isEqualTo(NOTE);
    }

    // Covers: 6.2 at most 5 from a catalog source (a request for 5 or fewer is kept as it is, with no note)
    @Test
    void aRequestForThree_atACatalogSource_isKept_withoutANote() {
        List<Integer> asked = new CopyOnWriteArrayList<>();
        Map<String, Object> result = search(List.of(CATALOG_SOURCE), Map.of(CATALOG, ten("c", asked)), 3);

        assertThat(items(result)).hasSize(3);
        assertThat(asked).containsExactly(3);
        assertThat(result).doesNotContainKey("note");
    }

    // Covers: 6.2 at most 5 from a catalog source (a store keeps max_results: ten from a store, no note)
    @Test
    void aStore_keepsMaxResults() {
        List<Integer> asked = new CopyOnWriteArrayList<>();
        Map<String, Object> result = search(List.of(A_STORE), Map.of(STORE, ten("s", asked)), 10);

        assertThat(items(result)).hasSize(10);
        assertThat(asked).containsExactly(10);
        assertThat(result).doesNotContainKey("note");
    }

    // Covers: 6.2 at most 5 from a catalog source (beside a store, the catalog source still gives at most 5, even when it
    // answers with more than it was asked for; the store fills the rest, round-robin)
    @Test
    void besideAStore_theCatalogSourceGivesAtMostFive() {
        List<Integer> catalogAsked = new CopyOnWriteArrayList<>();
        List<Integer> storeAsked = new CopyOnWriteArrayList<>();
        Map<String, Object> result = search(List.of(CATALOG_SOURCE, A_STORE),
                Map.of(CATALOG, ten("c", catalogAsked), STORE, ten("s", storeAsked)), 10);

        List<?> fromCatalog = items(result).stream().filter(i -> CATALOG.value().equals(((Map<?, ?>) i)
                .get("merchant_id"))).toList();
        assertThat(fromCatalog).hasSize(5);
        assertThat(items(result)).hasSize(10);
        assertThat(catalogAsked).containsExactly(5);
        assertThat(storeAsked).containsExactly(10);
        assertThat(result.get("note")).isEqualTo(NOTE);
    }
}
