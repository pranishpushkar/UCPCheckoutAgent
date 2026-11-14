package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.MerchantEntry;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** {@link CatalogClient} with the real transport against the reference merchant in this JVM (section 14, level 4). */
class CatalogContractTest extends ContractTestBase {

    private CatalogClient client() {
        UcpHttpGateway gateway = gateway();
        return new CatalogClient(gateway, CONFIG, new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()),
                Clock.systemUTC(), d -> { }, new SplittableRandom(3), new SequentialIdGenerator("k"));
    }

    private static <T> T value(CallOutcome<T> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<T>) outcome).value();
    }

    // Covers: FR1, 6.2 (search by title against the real merchant; price in minor units, variant id is the item id)
    @Test
    void search_findsTheMug_withItsPriceAndItemId() {
        CatalogPage page = value(client().search(new CatalogSearch("blue mug", 5)));

        assertThat(page.products()).hasSize(1);
        CatalogProduct mug = page.products().get(0);
        assertThat(mug.title()).isEqualTo("Blue Mug");
        assertThat(mug.variants().get(0).itemId()).isEqualTo("item_456");
        assertThat(mug.variants().get(0).price().minor()).isEqualTo(1200);
        assertThat(mug.variants().get(0).price().currency()).isEqualTo("USD");
        assertThat(mug.variants().get(0).availability().available()).isTrue();
    }

    // Covers: FR1 (the limit is honoured and the merchant says there is more)
    @Test
    void search_limit_pagesTheResults() {
        CatalogPage page = value(client().search(new CatalogSearch("", 2)));

        assertThat(page.products()).hasSize(2);
        assertThat(page.hasNextPage()).isTrue();
    }

    // Covers: FR1 (no match is Ok and empty, not an error)
    @Test
    void search_noMatch_isAnEmptyPage() {
        assertThat(value(client().search(new CatalogSearch("zzz", 5))).products()).isEmpty();
    }

    // Covers: FR1, section 20 (availability and price are read live and never cached: they follow the lab's changes)
    @Test
    void search_followsStockAndPriceChanges() {
        CatalogClient client = client();
        merchant.setStock("item_789", 0);
        merchant.setPrice("item_456", 1500);

        List<CatalogProduct> products = value(client.search(new CatalogSearch("", 10))).products();

        assertThat(products).filteredOn(p -> p.id().equals("item_789"))
                .allSatisfy(p -> assertThat(p.variants().get(0).availability().available()).isFalse());
        assertThat(products).filteredOn(p -> p.id().equals("item_456"))
                .allSatisfy(p -> assertThat(p.variants().get(0).price().minor()).isEqualTo(1500));
    }

    // Covers: FR1 (lookup by id; an unknown id is left out)
    @Test
    void lookup_returnsTheKnownIds_only() {
        List<CatalogProduct> products = value(client().lookup(List.of("item_123", "nope", "item_789")));

        assertThat(products).extracting(CatalogProduct::id).containsExactly("item_123", "item_789");
    }

    // Covers: FR1 (lookup needs ids; the port refuses an empty list before any call)
    @Test
    void lookup_emptyIds_isRefused() {
        assertThatThrownBy(() -> client().lookup(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: FR1 (one product in full)
    @Test
    void product_returnsTheProduct() {
        CatalogProduct p = value(client().product("item_789"));

        assertThat(p.title()).isEqualTo("Limited Sneakers");
        assertThat(p.variants().get(0).price().minor()).isEqualTo(12000);
    }

    // Covers: FR1, 8.1 (an unknown product is HTTP 200 with ucp.status error: a ProtocolError not_found, one request)
    @Test
    void product_unknown_isProtocolErrorNotFound() {
        CallOutcome<CatalogProduct> outcome = client().product("nope");

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.code()).isEqualTo("not_found");
            assertThat(e.httpStatus()).isEqualTo(200);
        });
    }

    // Covers: FR1, 8.5 (a merchant whose profile lacks the catalog capability is not asked: nothing is sent)
    @Test
    void capabilityMissing_isProtocolError_andSendsNothing() throws Exception {
        try (StubMerchant stub = new StubMerchant()) {
            // A profile with the checkout capability only: the captured fixture now lists the catalog too.
            String port = String.valueOf(stub.baseUrl().getPort());
            stub.on("GET /.well-known/ucp", StubMerchant.reply(200, "{\"ucp\":{\"version\":\"2026-08-25\","
                    + "\"capabilities\":{\"dev.ucp.shopping.checkout\":[{\"version\":\"2026-08-25\"}]},"
                    + "\"services\":{\"dev.ucp.shopping\":[{\"transport\":\"rest\",\"version\":\"2026-08-25\","
                    + "\"endpoint\":\"http://127.0.0.1:" + port + "/ucp/v1\"}]},"
                + "\"payment_handlers\":{\"dev.ucplab.mock_pay\":[{\"id\":\"mock_pay_1\",\"version\":\"2026-08-25\"}]}}}"));
            UcpHttpGateway gateway = new UcpHttpGateway(new MerchantEntry(SHOP, stub.baseUrl(), "Shop",
                    dev.ucplab.core.merchant.MerchantStatus.ACTIVE), CONFIG, new JdkHttpTransport(CONFIG),
                    new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), d -> { }, new SplittableRandom(3),
                    new SequentialIdGenerator("k"));
            CatalogClient client = new CatalogClient(gateway, CONFIG, new JdkHttpTransport(CONFIG),
                    new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), d -> { }, new SplittableRandom(3),
                    new SequentialIdGenerator("k"));

            CallOutcome<CatalogPage> outcome = client.search(new CatalogSearch("mug", 3));

            assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                    e -> assertThat(e.code()).isEqualTo("capability_missing"));
            assertThat(stub.requestsTo("POST /ucp/v1/catalog/search")).isEmpty();
        }
    }

    // Covers: FR1, 6.2 get_product (the real merchant's plain description reaches the product, by search and by
    // lookup)
    @Test
    void searchAndLookup_readTheMerchantsPlainDescription() {
        CatalogClient client = client();

        CatalogProduct searched = value(client.search(new CatalogSearch("blue mug", 5))).products().get(0);
        CatalogProduct looked = value(client.lookup(List.of("item_456"))).get(0);

        assertThat(searched.description()).contains("Blue Mug");
        assertThat(looked.description()).contains("Blue Mug");
    }
}
