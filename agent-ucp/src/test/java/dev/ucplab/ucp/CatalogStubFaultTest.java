package dev.ucplab.ucp;

import static dev.ucplab.ucp.StubMerchant.reply;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Catalog reads against a misbehaving stub (decision R), for what the fault proxy cannot do: check the headers and
 * bytes of a read (the proxy log records neither, I8), and answer with valid JSON that is semantically wrong (a decimal
 * price, a 202, a credential echoed in an error, a route the stub does not serve). The 429, 500, drop and garbled cases
 * moved to {@link ProxyCatalogFaultTest} in M4.
 */
class CatalogStubFaultTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final String SEARCH = "POST /ucp/v1/catalog/search";
    private static final String PRODUCT = "POST /ucp/v1/catalog/product";
    private static final UcpConfig CONFIG = new UcpConfig("profile=\"https://agent.example/.well-known/ucp\"",
            Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofMillis(400), 3, Duration.ofMillis(200),
            Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20);
    private static final String ONE_PRODUCT = "{\"ucp\":{\"version\":\"2026-08-25\"},\"products\":[{\"id\":\"item_456\","
            + "\"title\":\"Blue Mug\",\"variants\":[{\"id\":\"item_456\",\"title\":\"Blue Mug\","
            + "\"price\":{\"amount\":1200,\"currency\":\"USD\"},\"availability\":{\"available\":true}}]}]}";

    private StubMerchant stub;
    private CatalogClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        stub = new StubMerchant();
        String port = String.valueOf(stub.baseUrl().getPort());
        stub.on("GET /.well-known/ucp", reply(200, "{\"ucp\":{\"version\":\"2026-08-25\",\"capabilities\":{"
                + "\"dev.ucp.shopping.checkout\":[{\"version\":\"2026-08-25\"}],"
                + "\"dev.ucp.shopping.catalog.search\":[{\"version\":\"2026-08-25\"}],"
                + "\"dev.ucp.shopping.catalog.lookup\":[{\"version\":\"2026-08-25\"}]},"
                + "\"services\":{\"dev.ucp.shopping\":[{\"transport\":\"rest\",\"version\":\"2026-08-25\","
                + "\"endpoint\":\"http://127.0.0.1:" + port + "/ucp/v1\"}]},"
                + "\"payment_handlers\":{\"dev.ucplab.mock_pay\":[{\"id\":\"mock_pay_1\",\"version\":\"2026-08-25\"}]}}}"));
        UcpHttpGateway gateway = new UcpHttpGateway(new MerchantEntry(SHOP, stub.baseUrl(), "Shop", MerchantStatus.ACTIVE),
                CONFIG, new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(),
                sleeps::add, new SplittableRandom(5), new SequentialIdGenerator("s"));
        client = new CatalogClient(gateway, CONFIG, new JdkHttpTransport(CONFIG),
                new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), sleeps::add, new SplittableRandom(5),
                new SequentialIdGenerator("c"));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static CatalogSearch mug() {
        return new CatalogSearch("mug", 3);
    }

    // Covers: 8.2, 8.4 (a catalog read is a read: no Idempotency-Key, a fresh Request-Id and the UCP-Agent on every
    // attempt, the same body bytes, JSON content type)
    @Test
    void search_sendsNoIdempotencyKey_andAFreshRequestIdPerAttempt() {
        stub.on(SEARCH, reply(500, "{}"), reply(200, ONE_PRODUCT));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        List<StubMerchant.Seen> seen = stub.requestsTo(SEARCH);
        assertThat(seen).hasSize(2);
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.headers()).doesNotContainKey("idempotency-key");
            assertThat(s.headers()).containsKeys("request-id", "ucp-agent");
            assertThat(s.headers().get("content-type")).startsWith("application/json");
        });
        assertThat(seen.stream().map(s -> s.headers().get("request-id")).distinct()).hasSize(2);
        assertThat(seen.stream().map(s -> new String(s.body())).distinct())
                .containsExactly("{\"pagination\":{\"limit\":3},\"query\":\"mug\"}");
    }

    // Covers: 8.1 (valid JSON with a wrong field type, such as a decimal price, is invalid too)
    @Test
    void search_decimalPrice_isProtocolError() {
        stub.on(SEARCH, reply(200, ONE_PRODUCT.replace("1200", "12.5")));

        assertThat(client.search(mug())).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).isEqualTo("invalid_response"));
    }

    // Covers: 8.1 (a 404 or 400 on a read is a ProtocolError, not retried)
    @Test
    void product_400And404_areProtocolErrors_notRetried() {
        stub.on(PRODUCT, reply(404, "{\"code\":\"not_found\",\"content\":\"no route\"}"));

        assertThat(client.product("x")).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(404));
        assertThat(stub.requestsTo(PRODUCT)).hasSize(1);
    }

    // Covers: 8.1 (an odd status on a read, such as 202, is a ProtocolError)
    @Test
    void search_unexpectedStatus_isProtocolError() {
        stub.on(SEARCH, reply(202, "{}"));

        assertThat(client.search(mug())).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).isEqualTo("unexpected_status"));
    }

    // Covers: I8 (a credential-like header value echoed in a failure is scrubbed before it leaves the client)
    @Test
    void failureCauses_neverCarryAnAuthorizationValue() {
        stub.on(SEARCH, reply(400, "{\"code\":\"bad\",\"content\":\"Authorization: Bearer sekrit-123\"}"));

        assertThat(client.search(mug())).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.content() + e.code()).doesNotContain("sekrit-123"));
    }

    // Covers: 8.3, 8.5 (when the profile cannot be fetched nothing is sent to the catalog endpoint: NotSent)
    @Test
    void profileUnavailable_isNotSent() {
        stub.on("GET /.well-known/ucp", reply(500, "{}"));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(stub.requestsTo(SEARCH)).isEmpty();
    }

    // Covers: FR1 (lookup and product use their own paths and bodies)
    @Test
    void lookupAndProduct_postTheirOwnBodies() {
        stub.on("POST /ucp/v1/catalog/lookup", reply(200, ONE_PRODUCT))
                .on(PRODUCT, reply(200, "{\"product\":" + ONE_PRODUCT.substring(ONE_PRODUCT.indexOf("[{") + 1,
                        ONE_PRODUCT.lastIndexOf("]")) + "}"));

        assertThat(client.lookup(List.of("item_456", "item_123"))).isInstanceOf(CallOutcome.Ok.class);
        CallOutcome<CatalogProduct> p = client.product("item_456");

        assertThat(p).isInstanceOf(CallOutcome.Ok.class);
        assertThat(new String(stub.requestsTo("POST /ucp/v1/catalog/lookup").get(0).body()))
                .isEqualTo("{\"ids\":[\"item_456\",\"item_123\"]}");
        assertThat(new String(stub.requestsTo(PRODUCT).get(0).body())).isEqualTo("{\"id\":\"item_456\"}");
    }
}
