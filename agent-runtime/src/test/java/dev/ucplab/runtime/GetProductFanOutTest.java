package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.merchant.Availability;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@code get_product} asks every active merchant at once, through the same limit as {@code search_catalog} (m6a decision 5;
 * DESIGN 6.2, 19, I6): a hung merchant costs one limit and not a queue, an id at two merchants is answered from the lower
 * registry index whatever order they answered in, and the output and error codes are those of the sequential tool.
 */
class GetProductFanOutTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final MerchantId C = new MerchantId("merchant-c");

    private static MerchantEntry entry(MerchantId id) {
        return new MerchantEntry(id, URI.create("http://127.0.0.1:9101"), "Shop " + id.value(), MerchantStatus.ACTIVE);
    }

    private static CatalogProduct product(String id, String title, long price) {
        return new CatalogProduct(id, title, Optional.of("About " + title), List.of(new CatalogVariant(id, title,
                new Money(price, "USD"), new Availability(true, Optional.of("in_stock")))));
    }

    private static CatalogGateway catalog(Function<String, CallOutcome<CatalogProduct>> product) {
        return new CatalogGateway() {
            @Override
            public CallOutcome<CatalogPage> search(CatalogSearch request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CallOutcome<CatalogProduct> product(String id) {
                return product.apply(id);
            }
        };
    }

    private static CallOutcome<CatalogProduct> has(CatalogProduct p) {
        return new CallOutcome.Ok<>(200, p, List.of());
    }

    private static CallOutcome<CatalogProduct> notFound() {
        return new CallOutcome.ProtocolError<>(200, "not_found", "no such product", Optional.empty());
    }

    /** Blocks until interrupted (the fan-out's cancel) or three seconds, so a sequential tool is slow but a test ends. */
    private static CallOutcome<CatalogProduct> hang() {
        try {
            new CountDownLatch(1).await(3, TimeUnit.SECONDS);
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

    private static ToolPorts ports(Duration limit, Object... pairs) {
        Map<MerchantId, CatalogGateway> catalogs = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            catalogs.put((MerchantId) pairs[i], (CatalogGateway) pairs[i + 1]);
        }
        return new ToolPorts(Stubs.registry(catalogs.keySet().stream().map(GetProductFanOutTest::entry).toList()), catalogs,
                noOrchestrator()).withPerMerchant(limit);
    }

    private static ToolOutcome.Result get(ToolPorts ports, String id) {
        return (ToolOutcome.Result) new GetProductTool(ports).run(Stubs.CONTEXT, Map.of("item_id", id));
    }

    private static String merchantOf(ToolOutcome.Result r) {
        return (String) dev.ucplab.core.json.LenientJson.readObject(r.json()).get("merchant_id");
    }

    // Covers: 6.2, 19, I6 (a hung merchant costs one limit and not a queue: the answer comes from the one that has the item,
    // at about the limit)
    @Test
    void aHungMerchant_costsOneLimit_andTheOtherAnswers() {
        ToolPorts ports = ports(Duration.ofMillis(200), A, catalog(id -> hang()), B, catalog(id -> has(product(id, "Mug", 100))));

        long started = System.nanoTime();
        ToolOutcome.Result r = get(ports, "item_1");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(merchantOf(r)).isEqualTo("merchant-b");
        assertThat(ms).as("one limit, not the wait of the hung merchant").isLessThan(1500L);
    }

    // Covers: 6.2, 19 (two hung merchants cost one limit, not two)
    @Test
    void twoHungMerchants_costOneLimit_notTheSum() {
        ToolPorts ports = ports(Duration.ofMillis(300), A, catalog(id -> hang()), B, catalog(id -> hang()),
                C, catalog(id -> has(product(id, "Mug", 100))));

        long started = System.nanoTime();
        ToolOutcome.Result r = get(ports, "item_1");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(merchantOf(r)).isEqualTo("merchant-c");
        assertThat(ms).as("not the sum of two limits").isLessThan(580L);
    }

    // Covers: 6.2, 19, decision 5 (an id at two merchants is answered from the lower registry index, whatever order they
    // answered in: A answers last in one run and first in the other)
    @Test
    void anIdAtTwoMerchants_isTheLowerRegistryIndexs_whateverTheAnswerOrder() {
        for (boolean aFirst : new boolean[] {true, false}) {
            CountDownLatch firstDone = new CountDownLatch(1);
            CatalogGateway a = catalog(id -> {
                await(aFirst ? null : firstDone);
                firstDone.countDown();
                return has(product(id, "Mug from A", 100));
            });
            CatalogGateway b = catalog(id -> {
                await(aFirst ? firstDone : null);
                firstDone.countDown();
                return has(product(id, "Mug from B", 200));
            });

            ToolOutcome.Result r = get(ports(Duration.ofSeconds(5), A, a, B, b), "item_1");

            assertThat(merchantOf(r)).as("A first = %s", aFirst).isEqualTo("merchant-a");
            assertThat(r.json()).contains("\"price_minor\":100");
        }
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

    // Covers: 6.2 (an id at one merchant only is that merchant's, wherever it is in the registry)
    @Test
    void anIdAtOneMerchantOnly_isThatMerchants() {
        ToolPorts ports = ports(Duration.ofSeconds(5), A, catalog(id -> notFound()), B, catalog(id -> notFound()),
                C, catalog(id -> has(product(id, "Mug", 100))));

        assertThat(merchantOf(get(ports, "c_item"))).isEqualTo("merchant-c");
    }

    // Covers: 6.2 (the error codes are as they were: not_found when none has it, unavailable when one could not be asked and
    // none had it, and a merchant that is down does not hide an item another has)
    @Test
    void theErrorCodes_areUnchanged() {
        assertThat(get(ports(Duration.ofSeconds(5), A, catalog(id -> notFound()), B, catalog(id -> notFound())), "x").json())
                .isEqualTo("{\"error\":\"not_found\"}");
        assertThat(get(ports(Duration.ofMillis(100), A, catalog(id -> hang()), B, catalog(id -> notFound())), "x").json())
                .isEqualTo("{\"error\":\"unavailable\"}");
        assertThat(get(ports(Duration.ofSeconds(5), A, catalog(id -> new CallOutcome.NotSent<>("down")),
                B, catalog(id -> has(product(id, "Mug", 100)))), "x").json()).contains("\"merchant_id\":\"merchant-b\"");
        assertThat(get(ports(Duration.ofSeconds(5)), "x").json()).isEqualTo("{\"error\":\"not_found\"}");
    }

    // Covers: 6.2, M5 goldens (with one merchant the output is the sequential tool's: its fields, its wrapped title and
    // description, and no unavailable key)
    @Test
    void withOneMerchant_theOutputIsTheSequentialOutput_byteForByte() {
        CatalogProduct p = product("item_456", "Blue Mug", 1200);
        ToolOutcome.Result r = get(ports(Duration.ofSeconds(5), A, catalog(id -> has(p))), "item_456");

        assertThat(r.json()).isEqualTo("{\"currency\":\"USD\",\"description\":"
                + dev.ucplab.core.json.CanonicalJson.write(UntrustedText.wrap("merchant", "description", "About Blue Mug"))
                + ",\"in_stock\":true,\"item_id\":\"item_456\",\"merchant_id\":\"merchant-a\",\"price_minor\":1200,\"title\":"
                + dev.ucplab.core.json.CanonicalJson.write(ToolText.title(p, p.variants().getFirst())) + "}");
    }

    // Covers: 6.2 (a catalog that throws fails the tool, as it always did: a bug, not a failed merchant)
    @Test
    void aCatalogThatThrows_failsTheTool() {
        ToolPorts ports = ports(Duration.ofSeconds(5), A, catalog(id -> {
            throw new IllegalStateException("boom");
        }));

        assertThatThrownBy(() -> get(ports, "x")).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }
}
