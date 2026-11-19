package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.Orchestrator;
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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code search_catalog} asks every active merchant at once, with a limit for each (DESIGN 6.2, 19, I6): a merchant that
 * is slow or failed is left out and named in {@code unavailable}, the answer returns at about the limit and not the sum,
 * and the merge is round-robin in registry order whatever order the merchants answered in.
 */
class SearchFanOutTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final MerchantId C = new MerchantId("merchant-c");

    private static MerchantEntry entry(MerchantId id) {
        return new MerchantEntry(id, URI.create("http://127.0.0.1:9101"), "Shop " + id.value(), MerchantStatus.ACTIVE);
    }

    private static CatalogProduct product(String id, long price) {
        return new CatalogProduct(id, "Mug " + id, Optional.empty(), List.of(new CatalogVariant(id, "Mug " + id,
                new Money(price, "USD"), new Availability(true, Optional.of("in_stock")))));
    }

    private static CallOutcome<CatalogPage> page(String... ids) {
        List<CatalogProduct> products = new ArrayList<>();
        for (String id : ids) {
            products.add(product(id, 100));
        }
        return new CallOutcome.Ok<>(200, new CatalogPage(products, false), List.of());
    }

    /** A catalog whose search runs {@code behaviour}; the other calls are never made by search. */
    private static CatalogGateway catalog(Supplier<CallOutcome<CatalogPage>> behaviour) {
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                return behaviour.get();
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

    private static CallOutcome<CatalogPage> blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new CallOutcome.NotSent<>("cancelled");
    }

    private static Orchestrator noOrchestrator() {
        return (Orchestrator) Proxy.newProxyInstance(Orchestrator.class.getClassLoader(), new Class<?>[] {Orchestrator.class},
                (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private static ToolPorts ports(Duration limit, Map<MerchantId, CatalogGateway> catalogs) {
        List<MerchantEntry> entries = catalogs.keySet().stream().map(SearchFanOutTest::entry).toList();
        return new ToolPorts(Stubs.registry(entries), catalogs, noOrchestrator()).withPerMerchant(limit);
    }

    private static ToolOutcome.Result search(ToolPorts ports, int max) {
        return (ToolOutcome.Result) new SearchCatalogTool(ports).run(Stubs.CONTEXT,
                Map.of("query", "mug", "max_results", (long) max));
    }

    private static Map<MerchantId, CatalogGateway> catalogs(Object... pairs) {
        Map<MerchantId, CatalogGateway> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((MerchantId) pairs[i], (CatalogGateway) pairs[i + 1]);
        }
        return m;
    }

    private static List<String> itemIds(String json) {
        return dev.ucplab.core.json.LenientJson.readObject(json).get("items") instanceof List<?> l
                ? l.stream().map(i -> (String) ((Map<?, ?>) i).get("item_id")).toList() : List.of();
    }

    // Covers: 6.2 (the limit for each merchant defaults to 2 s)
    @Test
    void theDefaultLimit_isTwoSeconds() {
        assertThat(ToolPorts.DEFAULT_PER_MERCHANT).isEqualTo(Duration.ofSeconds(2));
        assertThat(new ToolPorts(Stubs.registry(List.of()), Map.of(), noOrchestrator()).perMerchant())
                .isEqualTo(Duration.ofSeconds(2));
    }

    // Covers: 6.2, 19, I6 (a hung merchant is left out after the limit and the call returns at about the limit; the answer
    // names it in unavailable and holds the others' items)
    @Test
    void aHungMerchant_isLeftOut_afterTheLimit_andNamedUnavailable() {
        ToolPorts ports = ports(Duration.ofMillis(150), catalogs(A, catalog(() -> page("a1")),
                B, catalog(SearchFanOutTest::blockUntilInterrupted), C, catalog(() -> page("c1"))));

        long started = System.nanoTime();
        ToolOutcome.Result r = search(ports, 5);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(r.code()).isEqualTo("ok");
        assertThat(itemIds(r.json())).containsExactly("a1", "c1");
        assertThat(r.json()).contains("\"unavailable\":[\"merchant-b\"]");
        assertThat(ms).as("returns at about the limit").isBetween(140L, 1500L);
    }

    // Covers: 6.2, 19 (two hung merchants cost one limit, not two: they are asked at once)
    @Test
    void twoHungMerchants_costOneLimit_notTheSum() {
        ToolPorts ports = ports(Duration.ofMillis(300), catalogs(A, catalog(SearchFanOutTest::blockUntilInterrupted),
                B, catalog(SearchFanOutTest::blockUntilInterrupted), C, catalog(() -> page("c1"))));

        long started = System.nanoTime();
        ToolOutcome.Result r = search(ports, 5);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(itemIds(r.json())).containsExactly("c1");
        assertThat(r.json()).contains("\"unavailable\":[\"merchant-a\",\"merchant-b\"]");
        assertThat(ms).as("not the sum of two limits").isLessThan(580L);
    }

    // Covers: 6.2, 19 (unavailable names exactly the merchants that failed, in registry order: a failed outcome counts, a
    // merchant with no catalog client too, and one that answered with no match does not)
    @Test
    void unavailable_namesExactlyTheFailedMerchants() {
        Map<MerchantId, CatalogGateway> catalogs = catalogs(A, catalog(() -> new CallOutcome.NotSent<>("down")),
                B, catalog(() -> page()), C, catalog(() -> new CallOutcome.ProtocolError<>(500, "internal", "x", Optional.empty())));
        MerchantId d = new MerchantId("merchant-d");
        List<MerchantEntry> entries = List.of(entry(A), entry(B), entry(C), entry(d));
        ToolPorts ports = new ToolPorts(Stubs.registry(entries), catalogs, noOrchestrator());

        ToolOutcome.Result r = search(ports, 5);

        assertThat(r.json()).isEqualTo("{\"items\":[],\"unavailable\":[\"merchant-a\",\"merchant-c\",\"merchant-d\"]}");
    }

    // Covers: 6.2 (a catalog that throws is a bug, not a failed merchant: the tool fails as it always did, so a run records
    // tool_error)
    @Test
    void aCatalogThatThrows_failsTheTool() {
        ToolPorts ports = ports(Duration.ofSeconds(5), catalogs(A, catalog(() -> page("a1")), B, catalog(() -> {
            throw new IllegalStateException("boom");
        })));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> search(ports, 5)).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    // Covers: 6.2 (when every merchant failed the answer is exactly {"error":"unavailable"})
    @Test
    void everyMerchantFailed_isTheUnavailableError() {
        ToolPorts ports = ports(Duration.ofMillis(100), catalogs(A, catalog(() -> new CallOutcome.NotSent<>("down")),
                B, catalog(SearchFanOutTest::blockUntilInterrupted)));

        ToolOutcome.Result r = search(ports, 5);

        assertThat(r.json()).isEqualTo("{\"error\":\"unavailable\"}");
        assertThat(r.code()).isEqualTo("unavailable");
    }

    // Covers: 6.2, 19 (items are merged round-robin in registry order, each merchant's own order kept, whatever order the
    // merchants answered in: A answers last in one run and first in the other, and the answer is the same)
    @Test
    void theMerge_isRoundRobinInRegistryOrder_whateverTheAnswerOrder() {
        List<String> seen = new ArrayList<>();
        for (boolean aFirst : new boolean[] {true, false}) {
            CountDownLatch firstDone = new CountDownLatch(1);
            CatalogGateway a = catalog(() -> {
                await(aFirst ? null : firstDone);
                if (aFirst) {
                    firstDone.countDown();
                }
                return page("a1", "a2", "a3");
            });
            CatalogGateway b = catalog(() -> {
                await(aFirst ? firstDone : null);
                if (!aFirst) {
                    firstDone.countDown();
                }
                return page("b1", "b2");
            });
            ToolPorts ports = ports(Duration.ofSeconds(5), catalogs(A, a, B, b, C, catalog(() -> page("c1"))));

            seen.add(String.join(",", itemIds(search(ports, 10).json())));
        }

        assertThat(seen).containsExactly("a1,b1,c1,a2,b2,a3", "a1,b1,c1,a2,b2,a3");
    }

    private static void await(CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Covers: 6.2, I6 (max_results is the total cap, kept across merchants)
    @Test
    void maxResults_isTheTotalCap() {
        ToolPorts ports = ports(Duration.ofSeconds(5), catalogs(A, catalog(() -> page("a1", "a2", "a3")),
                B, catalog(() -> page("b1", "b2", "b3"))));

        assertThat(itemIds(search(ports, 3).json())).containsExactly("a1", "b1", "a2");
        assertThat(itemIds(search(ports, 1).json())).containsExactly("a1");
    }

    // Covers: 6.2 (a variant whose id a tool would not accept back is left out, as before, and does not take a slot)
    @Test
    void anUnacceptableItemId_isLeftOut() {
        ToolPorts ports = ports(Duration.ofSeconds(5), catalogs(A, catalog(() -> page("bad id!", "a2")),
                B, catalog(() -> page("b1"))));

        assertThat(itemIds(search(ports, 5).json())).containsExactly("a2", "b1");
    }

    // Covers: 6.2, M5 goldens (with one merchant the output is what the sequential tool gave: its items in its own order,
    // capped, and no unavailable key)
    @Test
    void withOneMerchant_theOutputIsTheSequentialOutput_byteForByte() {
        ToolPorts ports = ports(Duration.ofSeconds(5), catalogs(A, catalog(() -> page("a1", "a2", "a3"))));

        ToolOutcome.Result r = search(ports, 2);

        assertThat(r.json()).isEqualTo("{\"items\":[" + item("a1") + "," + item("a2") + "]}");
    }

    private static String item(String id) {
        return "{\"currency\":\"USD\",\"in_stock\":true,\"item_id\":\"" + id + "\",\"merchant_id\":\"merchant-a\","
                + "\"price_minor\":100,\"title\":" + dev.ucplab.core.json.CanonicalJson.write(
                        dev.ucplab.runtime.ToolText.title(product(id, 100), product(id, 100).variants().getFirst()))
                + "}";
    }

    // Covers: 6.2 (no active merchant is an empty list, as before, not an error)
    @Test
    void noActiveMerchant_isAnEmptyList() {
        ToolPorts ports = new ToolPorts(Stubs.registry(List.of()), Map.of(), noOrchestrator());

        assertThat(search(ports, 5).json()).isEqualTo("{\"items\":[]}");
    }

    private static CallOutcome<CatalogPage> after(long millis, String... ids) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CallOutcome.NotSent<>("cancelled");
        }
        return page(ids);
    }

    // Covers: 6.2, 26.7 (a catalog source waits 5 s by default, a store 2 s)
    @Test
    void theCatalogSourcesLimit_isFiveSeconds() {
        assertThat(ToolPorts.DEFAULT_CATALOG_SOURCE).isEqualTo(Duration.ofSeconds(5));
        assertThat(new ToolPorts(Stubs.registry(List.of()), Map.of(), noOrchestrator()).catalogSource())
                .isEqualTo(Duration.ofSeconds(5));
    }

    // Covers: 6.2, 26.7, 19 (a catalog source is waited for up to its own limit, longer than a store's: one that answers
    // after the store limit is kept, while a store that answers as late is left out and named unavailable)
    @Test
    void aCatalogSource_isWaitedForUpToItsOwnLimit_aStoreUpToTheStoreLimit() {
        MerchantEntry catalogSource = new MerchantEntry(A, URI.create("https://catalog.shopify.com/api/ucp/mcp"),
                "Shopify catalog", MerchantStatus.ACTIVE, Transport.CATALOG, Completion.LINK, false, Optional.empty());
        Map<MerchantId, CatalogGateway> catalogs = catalogs(A, catalog(() -> after(400, "a1")),
                B, catalog(() -> after(400, "b1")));
        ToolPorts ports = new ToolPorts(Stubs.registry(List.of(catalogSource, entry(B))), catalogs, noOrchestrator())
                .withPerMerchant(Duration.ofMillis(150)).withCatalogSource(Duration.ofSeconds(3));

        long started = System.nanoTime();
        ToolOutcome.Result r = search(ports, 5);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(itemIds(r.json())).containsExactly("a1");
        assertThat(r.json()).contains("\"unavailable\":[\"merchant-b\"]");
        assertThat(ms).as("returns when the catalog has answered, not at its limit").isBetween(380L, 2500L);
    }
}
