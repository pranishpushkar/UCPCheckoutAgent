package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Money;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@code search_catalog} and {@code get_product} for the run's buyer country (6.2, 23.1, 26.7): the country comes from
 * the run, never the model; the result names it, our own text; a catalog's items in another currency are counted, a
 * number only.
 */
class SearchCatalogBuyerCountryTest {

    private static final BuyerCountry INDIA = new BuyerCountry("IN");

    private static CatalogProduct board(String n, long minor, String currency) {
        return new CatalogProduct("gid://shopify/p/FAKEUPID00000" + n, "Example Snowboard " + n,
                List.of(new CatalogVariant("gid://shopify/ProductVariant/100000" + n, "Example Snowboard " + n,
                        new Money(minor, currency), new Availability(true, Optional.empty()),
                        Optional.of("Example Shop " + n), List.of())));
    }

    private final List<CatalogSearch> asked = new CopyOnWriteArrayList<>();
    private final List<BuyerCountry> productReads = new CopyOnWriteArrayList<>();
    private int otherCurrency;

    private ToolRegistry registry() {
        CatalogGateway recording = new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                asked.add(request);
                return new CallOutcome.Ok<>(200, new CatalogPage(List.of(board("1", 4_270_000, "INR")), false,
                        otherCurrency), List.of());
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                productReads.add(BuyerCountry.US);
                return new CallOutcome.Ok<>(200, board("1", 4_270_000, "INR"), List.of());
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id, BuyerCountry shipTo) {
                productReads.add(shipTo);
                return new CallOutcome.Ok<>(200, board("1", 4_270_000, "INR"), List.of());
            }
        };
        return ToolRegistry.of(Stubs.ports(recording, new Stubs.RecordingOrchestrator()), (q, t) -> Optional.empty(),
                Duration.ofMinutes(5));
    }

    private Map<String, Object> search(ToolContext context) {
        String json = registry().find("search_catalog").orElseThrow()
                .run(context, Map.of("query", "snowboard", "max_results", 10)).json();
        ResultFields.check(json);
        return CanonicalJson.readObject(json);
    }

    // Covers: 6.2, 23.1, 26.7 (the search asks for the run's buyer country, from the run and never from the model, and
    // the result names it: the code and its English name, our own text; a run with no country named searches for US)
    @Test
    void theSearch_isForTheRunsCountry_andTheResultNamesIt() {
        Map<String, Object> india = search(new ToolContext(Stubs.ALICE, new RunId("r"), Optional.empty(), INDIA));
        Map<String, Object> us = search(new ToolContext(Stubs.ALICE, new RunId("r")));

        assertThat(asked).extracting(CatalogSearch::shipTo).containsExactly(INDIA, BuyerCountry.US);
        assertThat(india).containsEntry("ships_to", Map.of("country", "IN", "country_name", "India"));
        assertThat(us).containsEntry("ships_to", Map.of("country", "US", "country_name", "United States"));
    }

    // Covers: 6.2, 26.7, I6 (items a catalog left out for their currency are counted, a number only; with none left out
    // there is no other_currency; a budget in the country's currency is still sent as the price limit)
    @Test
    void otherCurrencies_areCounted_aNumberOnly() {
        otherCurrency = 2;
        Map<String, Object> counted = search(new ToolContext(Stubs.ALICE, new RunId("r"),
                Optional.of(new Budget(5_000_000, "INR")), INDIA));
        otherCurrency = 0;
        Map<String, Object> none = search(new ToolContext(Stubs.ALICE, new RunId("r"), Optional.empty(), INDIA));

        assertThat(counted).containsEntry("other_currency", Map.of("count", 2L));
        assertThat(none).doesNotContainKey("other_currency");
        assertThat(asked.getFirst().maxPrice()).contains(new Money(5_000_000, "INR"));
    }

    // Covers: 6.2, 26.7 (get_product reads the product for the run's buyer country)
    @Test
    void getProduct_readsForTheRunsCountry() {
        String json = registry().find("get_product").orElseThrow().run(new ToolContext(Stubs.ALICE, new RunId("r"),
                Optional.empty(), INDIA), Map.of("item_id", "gid://shopify/ProductVariant/1000001")).json();

        assertThat(json).doesNotContain("\"error\"");
        assertThat(productReads).containsExactly(INDIA);
    }
}
