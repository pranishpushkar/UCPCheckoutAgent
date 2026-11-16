package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.ucp.auth.ClientCredentials;
import dev.ucplab.ucp.mcp.McpStore;
import dev.ucplab.ucp.mcp.StoreHeaders;
import dev.ucplab.ucp.registry.BuyerIp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link McpCatalogGateway} against the store's recorded search (H5 C6; FR1, 6.2, 8.4, 19). The shared
 * {@code CatalogGatewayContract} does not apply: it needs a catalog serving the lab's products, and a lookup and a
 * product call, which 8.4 does not offer over MCP; the recordings hold the store's own three products.
 */
class McpCatalogGatewayTest {

    final McpMerchantGatewayTest setup = new McpMerchantGatewayTest();

    McpCatalogGateway catalog(Optional<BuyerIp> ip) {
        return new McpCatalogGateway(setup.gateway(McpMerchantGatewayTest.entry(Completion.HANDOFF), ip,
                new ClientCredentials("client-1", "secret-1"), Set.of()));
    }

    @SuppressWarnings("unchecked")
    static <T> T ok(CallOutcome<T> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<T>) outcome).value();
    }

    // Covers: FR1, 8.4 (the recorded search: the store's products with their variant ids and prices, through
    // search_catalog with the recorded arguments, the token and the buyer IP)
    @Test
    void search_readsTheRecordedAnswer() {
        setup.store.answer("search_catalog", "catalog_search_store.json");

        CatalogPage page = ok(catalog(Optional.of(new BuyerIp("203.0.113.77"))).search(new CatalogSearch("snowboard", 10)));

        assertThat(page.products()).extracting(CatalogProduct::title).containsExactly("The Complete Snowboard",
                "The Multi-managed Snowboard", "The Compare at Price Snowboard");
        assertThat(page.products().getFirst().variants().getFirst().itemId())
                .isEqualTo("gid://shopify/ProductVariant/51192065753174");
        assertThat(page.hasNextPage()).isFalse();
        McpStore.Sent sent = setup.store.toolCalls().getFirst();
        assertThat(sent.tool()).isEqualTo("search_catalog");
        assertThat(sent.arguments().get("catalog")).isEqualTo(Map.of("query", "snowboard"));
        assertThat(sent.header("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(sent.header(StoreHeaders.BUYER_IP_HEADER)).isEqualTo("203.0.113.77");
    }

    // Covers: FR1, 6.2 (the page is cut to the limit, and then says there is more)
    @Test
    void search_withALimit_isCut_andSaysThereIsMore() {
        setup.store.answer("search_catalog", "catalog_search_store.json");

        CatalogPage page = ok(catalog(Optional.empty()).search(new CatalogSearch("snowboard", 2)));

        assertThat(page.products()).hasSize(2);
        assertThat(page.hasNextPage()).isTrue();
    }

    // Covers: 8.4, 8.1, 19 (the recorded refusal of a search sent without the buyer IP: HTTP 422 with a JSON-RPC
    // error is a ProtocolError keeping the status, its code from the bare message; the merchant's data is not read)
    @Test
    void search_recordedRefusal_isAProtocolError() {
        Map<String, Object> recorded = LenientJson.readObject(McpStore.fixture("catalog_search_store.error.json"));
        setup.store.raw("search_catalog", ((Long) recorded.get("http_status")).intValue(), (String) recorded.get("body"));

        assertThat(catalog(Optional.empty()).search(new CatalogSearch("snowboard", 10)))
                .isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("authenticationfailed");
                    assertThat(e.content()).doesNotContain("buyer IP");
                });
    }

    // Covers: 8.4 (no lookup tool over MCP: lookup and product send nothing and answer capability_missing, status 0)
    @Test
    void lookupAndProduct_sendNothing() {
        McpCatalogGateway c = catalog(Optional.empty());

        assertThat(c.lookup(List.of("gid://shopify/Product/1"))).isEqualTo(new CallOutcome.ProtocolError<>(0,
                "capability_missing", McpCatalogGateway.NO_LOOKUP, Optional.empty()));
        assertThat(c.product("gid://shopify/Product/1")).isEqualTo(new CallOutcome.ProtocolError<>(0,
                "capability_missing", McpCatalogGateway.NO_LOOKUP, Optional.empty()));
        assertThatThrownBy(() -> c.lookup(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(setup.store.sent()).isEmpty();
    }

    // Covers: 8.2, 26.4 (a search refused for its token is tried once more with a new token)
    @Test
    void search_refusedForItsToken_isTriedOnceMore() {
        setup.store.error("search_catalog", -32000, "AuthenticationFailed").answer("search_catalog",
                "catalog_search_store.json");

        assertThat(ok(catalog(Optional.empty()).search(new CatalogSearch("snowboard", 10))).products()).hasSize(3);
        assertThat(setup.store.toolCalls()).extracting(s -> s.header("Authorization"))
                .containsExactly("Bearer tok-1", "Bearer tok-2");
    }
}
