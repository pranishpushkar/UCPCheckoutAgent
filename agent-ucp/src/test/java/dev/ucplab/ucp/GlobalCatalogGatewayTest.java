package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.CatalogVariant;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Money;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.auth.TokenProvider;
import dev.ucplab.ucp.mcp.McpStore;
import dev.ucplab.ucp.mcp.StoreHeaders;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link GlobalCatalogGateway} against the Global Catalog's recorded answers, stripped and owner-approved
 * ({@code shopify-catalog/}; 26.7, 19, 6.2). The catalog is a loopback stub: nothing here reaches Shopify.
 */
class GlobalCatalogGatewayTest {

    static final MerchantId CATALOG = new MerchantId("shopify-catalog");
    static final URI URL = URI.create("http://127.0.0.1:8191/api/ucp/mcp");
    static final String PROFILE = "https://agent.example/.well-known/ucp/agent.json";

    static MerchantEntry entry() {
        return new MerchantEntry(CATALOG, URL, "Shopify catalog", MerchantStatus.ACTIVE, Transport.CATALOG,
                Completion.LINK, false, Optional.empty());
    }

    final McpStore store = new McpStore();
    final McpMerchantGatewayTest.TokenEndpoint tokenEndpoint = new McpMerchantGatewayTest.TokenEndpoint();
    final TestClock clock = new TestClock();

    GlobalCatalogGateway gateway(MerchantEntry entry) {
        ClientCredentials credentials = new ClientCredentials("client-1", "secret-1");
        TokenProvider tokens = new TokenProvider(URI.create("https://api.shopify.com/auth/access_token"), credentials,
                tokenEndpoint, clock, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(5));
        return new GlobalCatalogGateway(entry, McpMerchantGatewayTest.CONFIG, store,
                new CircuitBreakers(McpMerchantGatewayTest.CONFIG, clock), clock, d -> { }, new SplittableRandom(3),
                new SequentialIdGenerator("c"), "2026-04-08", Optional.of(tokens), credentials.redactable());
    }

    GlobalCatalogGateway gateway() {
        return gateway(entry());
    }

    static String fixture(String name) {
        return McpStore.catalogFixture(name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> content(Map<String, Object> answer) {
        return (Map<String, Object>) ((Map<String, Object>) answer.get("result")).get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    static <T> T ok(CallOutcome<T> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<T>) outcome).value();
    }

    // Covers: 26.7, 6.2, 19 (the recorded search: one item per variant, with its seller's name and the USD price; the
    // arguments are US/USD, ships_to US and available only, through the agent profile, with the owner's token and never
    // the buyer IP)
    @Test
    void search_sendsTheRecordedArguments_andReadsEachOffersSeller() {
        store.answerText("search_catalog", fixture("global_search_us.json"));

        CatalogPage page = ok(gateway().search(new CatalogSearch("snowboard", 10)));

        assertThat(page.products()).extracting(CatalogProduct::title)
                .containsExactly("Example Snowboard 1", "Example Snowboard 2", "Example Snowboard 3");
        CatalogVariant first = page.products().getFirst().variants().getFirst();
        assertThat(first.itemId()).isEqualTo("gid://shopify/ProductVariant/1000001");
        assertThat(first.price()).isEqualTo(new Money(30495, "USD"));
        assertThat(first.availability().available()).isTrue();
        assertThat(first.seller()).contains("Example Shop A");
        assertThat(page.hasNextPage()).isTrue();

        McpStore.Sent sent = store.toolCalls().getFirst();
        assertThat(sent.uri()).isEqualTo(URL.toString());
        assertThat(sent.tool()).isEqualTo("search_catalog");
        assertThat(sent.arguments().get("catalog")).isEqualTo(Map.of(
                "query", "snowboard",
                "context", Map.of("address_country", "US", "currency", "USD"),
                "filters", Map.of("available", true, "ships_to", Map.of("country", "US")),
                "pagination", Map.of("limit", 10L)));
        assertThat(sent.arguments().get("meta")).isEqualTo(Map.of("ucp-agent", Map.of("profile", PROFILE)));
        assertThat(sent.header("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(sent.headers().keySet()).noneMatch(k -> k.equalsIgnoreCase(StoreHeaders.BUYER_IP_HEADER));
    }

    // Covers: 26.7, I6 (no URL reaches a search result: a variant carries no checkout_url, product url or seller url)
    @Test
    void search_resultHoldsNoUrl() {
        store.answerText("search_catalog", fixture("global_search_us.json"));

        CatalogPage page = ok(gateway().search(new CatalogSearch("snowboard", 10)));

        assertThat(page.toString()).doesNotContain("http").doesNotContain("shop-a.example")
                .doesNotContain("myshopify");
    }

    // Covers: 26.7 (a variant priced in another currency is left out, and a product left with no variant with it)
    @Test
    @SuppressWarnings("unchecked")
    void search_leavesOutAVariantNotInUsd() {
        Map<String, Object> answer = LenientJson.readObject(fixture("global_search_us.json"));
        Map<String, Object> variant = (Map<String, Object>) ((List<Map<String, Object>>) ((List<Map<String, Object>>)
                content(answer).get("products")).getFirst().get("variants")).getFirst();
        ((Map<String, Object>) variant.get("price")).put("currency", "CAD");
        store.answerText("search_catalog", McpStore.json(answer));

        CatalogPage page = ok(gateway().search(new CatalogSearch("snowboard", 10)));

        assertThat(page.products()).extracting(CatalogProduct::title)
                .containsExactly("Example Snowboard 2", "Example Snowboard 3");
    }

    // Covers: 26.7, 26.2 (an answer at another UCP version is unreadable: a ProtocolError, never a page)
    @Test
    void search_atAnotherVersion_isUnreadable() {
        store.answerText("search_catalog", fixture("global_search_us.json").replace("\"2026-04-08\"", "\"2026-01-11\""));

        assertThat(gateway().search(new CatalogSearch("snowboard", 10))).isInstanceOf(CallOutcome.ProtocolError.class);
    }

    // Covers: 20, 26.7 (a catalog source's results are never cached: two searches are two calls)
    @Test
    void search_isNeverCached() {
        store.answerText("search_catalog", fixture("global_search_us.json"))
                .answerText("search_catalog", fixture("global_search_us.json"));
        GlobalCatalogGateway g = gateway();

        ok(g.search(new CatalogSearch("snowboard", 10)));
        ok(g.search(new CatalogSearch("snowboard", 10)));

        assertThat(store.toolCalls()).hasSize(2);
    }

    // Covers: 26.7, 6.2, 6.4 (get_product of a variant: the product with its text, markup removed, read with the US
    // context)
    @Test
    void product_readsGetProduct() {
        store.answerText("get_product", fixture("global_get_product_us.json"));

        CatalogProduct product = ok(gateway().product("gid://shopify/ProductVariant/1000001"));

        assertThat(product.title()).isEqualTo("Example Snowboard 1");
        assertThat(product.description()).contains("Our own test text for board 1.");
        assertThat(product.variants().getFirst().seller()).contains("Example Shop A");
        McpStore.Sent sent = store.toolCalls().getFirst();
        assertThat(sent.tool()).isEqualTo("get_product");
        assertThat(sent.arguments().get("catalog")).isEqualTo(Map.of("id", "gid://shopify/ProductVariant/1000001",
                "context", Map.of("address_country", "US", "currency", "USD")));
    }

    // Covers: I12, 26.5, 26.7, N1 (a catalog source takes no checkout call: create, update, complete, cancel and get
    // send nothing and are NotSent; lookup sends nothing; discovery sends nothing and offers search)
    @Test
    void checkoutCalls_andDiscovery_sendNothing() {
        GlobalCatalogGateway g = gateway();
        SessionId session = new SessionId("link:gid://shopify/ProductVariant/1000001");

        assertThat(g.create(McpMerchantGatewayTest.ONE_BOARD, McpMerchantGatewayTest.CREATE_KEY))
                .isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.complete(session, new FrozenBody("{}"), McpMerchantGatewayTest.COMPLETE_KEY))
                .isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.cancel(session, McpMerchantGatewayTest.CANCEL_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.get(session)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.lookup(List.of("gid://shopify/ProductVariant/1000001")))
                .isInstanceOf(CallOutcome.ProtocolError.class);
        assertThat(g.discover(CATALOG).capabilities()).contains(CatalogClient.SEARCH_CAPABILITY);
        assertThat(store.sent()).isEmpty();
        assertThat(tokenEndpoint.calls).isEmpty();
    }

    // Covers: 19, 26.7 (the gateway is for a catalog entry only)
    @Test
    void aCatalogGateway_isForACatalogEntryOnly() {
        assertThatThrownBy(() -> gateway(McpMerchantGatewayTest.entry(Completion.HANDOFF)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
