package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.QuoteShape;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class UcpHttpGatewayTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final MerchantEntry MERCHANT = new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop",
            MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey UPDATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.UPDATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final IdempotencyKey CANCEL_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CANCEL, 0);
    private static final SessionId CHK_1001 = new SessionId("chk_1001");
    private static final String TOKEN = "tok_canary_9f3a";

    private static byte[] fixture(String name) {
        try (InputStream in = UcpHttpGatewayTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, String fixture) {
        return new HttpReply(status, Map.of(), fixture(fixture));
    }

    private static HttpReply reply(int status, String body, boolean raw) {
        return new HttpReply(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Plays scripted steps per "METHOD /path" and records every call. A step is an HttpReply or an IOException. */
    private static final class RoutedTransport implements HttpTransport {
        final Map<String, Deque<Object>> script = new HashMap<>();
        final List<HttpCall> calls = new ArrayList<>();

        RoutedTransport on(String route, Object... steps) {
            script.computeIfAbsent(route, k -> new ArrayDeque<>()).addAll(List.of(steps));
            return this;
        }

        List<HttpCall> callsTo(String route) {
            return calls.stream().filter(c -> (c.method() + " " + c.uri().getPath()).equals(route)).toList();
        }

        @Override
        public HttpReply send(HttpCall call) throws IOException {
            calls.add(call);
            Deque<Object> steps = script.get(call.method() + " " + call.uri().getPath());
            if (steps == null || steps.isEmpty()) {
                throw new AssertionError("unscripted call " + call);
            }
            Object step = steps.size() > 1 ? steps.removeFirst() : steps.peekFirst();
            if (step instanceof IOException e) {
                throw e;
            }
            return (HttpReply) step;
        }
    }

    private final TestClock clock = new TestClock();
    private final RoutedTransport transport = new RoutedTransport();
    private final CircuitBreakers breakers = new CircuitBreakers(CONFIG, clock);
    private final List<Duration> sleeps = new ArrayList<>();

    private UcpHttpGateway gateway() {
        return gateway(CONFIG);
    }

    private UcpHttpGateway gateway(UcpConfig config) {
        return new UcpHttpGateway(MERCHANT, config, transport, new CircuitBreakers(config, clock), clock, sleeps::add,
                new SplittableRandom(7), new SequentialIdGenerator("req"));
    }

    private static UcpConfig noRetries() {
        return new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(), CONFIG.completeTimeout(),
                0, CONFIG.backoffInitial(), CONFIG.backoffCap(), CONFIG.breakerThreshold(), CONFIG.breakerOpenFor(),
                CONFIG.maxResponseBytes());
    }

    private RoutedTransport withProfile() {
        return transport.on("GET /.well-known/ucp", reply(200, "discovery"));
    }

    private static CreateCheckout createRequest() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
    }

    // ---------------------------------------------------------------- binding and discovery (J, K)

    // Covers: 8.5, decision J (a gateway is bound to one merchant: discover for any other id is refused)
    @Test
    void discover_refusesAnotherMerchantId() {
        assertThatThrownBy(() -> gateway().discover(new MerchantId("shop-2"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shop-1");
        assertThat(transport.calls).isEmpty();
    }

    // Covers: 8.5, decision K, 2.1 (the profile is read from the merchant's real /.well-known/ucp: version, endpoint,
    // capabilities and payment handler ids)
    @Test
    void discover_readsTheRealProfile() {
        withProfile();

        MerchantProfile p = gateway().discover(SHOP);

        assertThat(p.merchant()).isEqualTo(SHOP);
        assertThat(p.ucpVersion()).isEqualTo("2026-08-25");
        assertThat(p.checkoutEndpoint()).isEqualTo(URI.create("http://127.0.0.1:8081/ucp/v1"));
        assertThat(p.capabilities()).containsExactlyInAnyOrder("dev.ucp.shopping.checkout",
                "dev.ucp.shopping.catalog.search", "dev.ucp.shopping.catalog.lookup");
        assertThat(p.paymentHandlers()).containsExactly("mock_pay_1");
        HttpCall call = transport.calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri()).isEqualTo(URI.create("http://127.0.0.1:8081/.well-known/ucp"));
        assertThat(call.headers()).containsEntry("UCP-Agent", CONFIG.ucpAgent()).containsKey("Request-Id");
    }

    // Covers: decision K, section 20 (cached per gateway for 5 minutes, then fetched again)
    @Test
    void discover_isCachedFiveMinutes() {
        withProfile();
        UcpHttpGateway gateway = gateway();

        gateway.discover(SHOP);
        gateway.discover(SHOP);
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        gateway.discover(SHOP);
        assertThat(transport.callsTo("GET /.well-known/ucp")).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        gateway.discover(SHOP);

        assertThat(transport.callsTo("GET /.well-known/ucp")).hasSize(2);
    }

    // Covers: decision K, 8.2 (discover retries like get: two refused connections, then the profile)
    @Test
    void discover_retriesLikeGet() {
        withProfile();
        transport.script.get("GET /.well-known/ucp").addFirst(new ConnectException("refused"));
        transport.script.get("GET /.well-known/ucp").addFirst(new HttpTimeoutException("slow"));

        assertThat(gateway().discover(SHOP).ucpVersion()).isEqualTo("2026-08-25");
        assertThat(transport.callsTo("GET /.well-known/ucp")).hasSize(3);
    }

    // Covers: decision K (a profile that cannot be had, or read, throws DiscoveryFailedException with no body in it)
    @Test
    void discover_throwsDiscoveryFailed_whenProfileCannotBeFetchedOrRead() {
        transport.on("GET /.well-known/ucp", new HttpReply(500, Map.of(), ("echo " + TOKEN).getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageNotContaining(TOKEN);

        RoutedTransport t2 = new RoutedTransport().on("GET /.well-known/ucp", reply(200, "not json", true));
        UcpHttpGateway g2 = new UcpHttpGateway(MERCHANT, CONFIG, t2, breakers, clock, d -> { },
                new SplittableRandom(1), new SequentialIdGenerator("r"));
        assertThatThrownBy(() -> g2.discover(SHOP)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining("invalid profile");
    }

    // Covers: decision K (version, capability and endpoint host are checked: a create is NotSent and nothing is sent to
    // the checkout endpoint)
    @Test
    void create_isNotSent_whenProfileIsIncompatible() {
        String profile = new String(fixture("discovery"), StandardCharsets.UTF_8);
        Map<String, String> bad = Map.of(
                "version", profile.replace("\"version\":\"2026-08-25\"}}", "\"version\":\"2026-04-08\"}}"),
                "capability", profile.replace("dev.ucp.shopping.checkout", "dev.ucp.shopping.cart"),
                "endpoint", profile.replace("127.0.0.1:8081/ucp/v1", "evil.example:8081/ucp/v1"));
        for (var e : bad.entrySet()) {
            assertThat(e.getValue()).as(e.getKey()).isNotEqualTo(profile);
            RoutedTransport t = new RoutedTransport().on("GET /.well-known/ucp", reply(200, e.getValue(), true));
            UcpHttpGateway g = new UcpHttpGateway(MERCHANT, CONFIG, t, breakers, clock, d -> { },
                    new SplittableRandom(1), new SequentialIdGenerator("r"));

            assertThat(g.create(createRequest(), CREATE_KEY)).as(e.getKey()).isInstanceOf(CallOutcome.NotSent.class);
            assertThat(t.callsTo("POST /ucp/v1/checkout-sessions")).as(e.getKey()).isEmpty();
        }
    }

    // Covers: decision K (when discovery fails no create is sent to that merchant)
    @Test
    void create_isNotSent_whenDiscoveryFails() {
        transport.on("GET /.well-known/ucp", new ConnectException("refused"));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("profile unavailable"));
        assertThat(transport.callsTo("POST /ucp/v1/checkout-sessions")).isEmpty();
    }

    // ---------------------------------------------------------------- the six calls

    // Covers: 8.4, I7, decision H (create: POST to the discovered endpoint with the canonical body bytes, the key, a
    // Request-Id and UCP-Agent; 201 is Ok)
    @Test
    void create_postsCanonicalBodyWithHeaders() {
        withProfile().on("POST /ucp/v1/checkout-sessions", reply(201, "create_ready"));

        CallOutcome<CheckoutView> outcome = gateway().create(createRequest(), CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
            assertThat(ok.httpStatus()).isEqualTo(201);
            assertThat(((CheckoutView) ok.value()).id()).isEqualTo(CHK_1001);
        });
        HttpCall post = transport.callsTo("POST /ucp/v1/checkout-sessions").get(0);
        assertThat(post.body().orElseThrow().json()).isEqualTo(CheckoutWire.createBody(createRequest()));
        assertThat(post.headers()).containsEntry("Idempotency-Key", CREATE_KEY.value())
                .containsEntry("UCP-Agent", CONFIG.ucpAgent()).containsKey("Request-Id")
                .containsEntry("Content-Type", "application/json").doesNotContainKey("Authorization");
    }

    // Covers: 8.1, decision N (create with every item unavailable is Rejected)
    @Test
    void create_isRejected_forTheAllUnavailableEnvelope() {
        withProfile().on("POST /ucp/v1/checkout-sessions", reply(200, "create_all_unavailable"));

        assertThat(gateway().create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.Rejected.class);
    }

    // Covers: 8.4, decision L (update: PUT to the session with exactly the bytes of CheckoutWire.updateBody, which
    // carry the line ids and the buyer fields of the last view)
    @Test
    void update_putsLineIdsAndFullBuyer() {
        withProfile().on("PUT /ucp/v1/checkout-sessions/chk_1001", reply(200, "get_ready"));
        QuoteShape last = new QuoteShape(List.of(new LineView("item_123", 2, 2500, Optional.of("li_1"))),
                Optional.of(new Buyer(Optional.of("old@example.com"), Optional.of("Ada"), Optional.of("Lovelace"),
                        Optional.empty())));
        UpdateCheckout request = UpdateCheckout.from(List.of(new ProposedLine("item_123", 2)),
                Buyer.ofEmail("a@example.com"), Optional.of(last));

        CallOutcome<CheckoutView> outcome = gateway().update(CHK_1001, request, UPDATE_KEY);

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        HttpCall put = transport.callsTo("PUT /ucp/v1/checkout-sessions/chk_1001").get(0);
        assertThat(put.body().orElseThrow().json()).isEqualTo(CheckoutWire.updateBody(request))
                .contains("\"id\":\"li_1\"").contains("\"first_name\":\"Ada\"").contains("\"last_name\":\"Lovelace\"")
                .contains("\"email\":\"a@example.com\"");
        assertThat(put.headers()).containsEntry("Idempotency-Key", UPDATE_KEY.value());
    }

    // Covers: 7.4, 8.4, I7 (complete: the frozen bytes go out unchanged under the same key, on every attempt)
    @Test
    void complete_sendsFrozenBytesUnderTheKey() {
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
        withProfile().on("POST /ucp/v1/checkout-sessions/chk_1001/complete", new ConnectException("refused"),
                new ConnectException("refused"), reply(200, "complete_price_changed"));

        CallOutcome<CheckoutView> outcome = gateway().complete(CHK_1001, body, COMPLETE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.INCOMPLETE));
        List<HttpCall> posts = transport.callsTo("POST /ucp/v1/checkout-sessions/chk_1001/complete");
        assertThat(posts).hasSize(3).allSatisfy(c -> {
            assertThat(c.body().orElseThrow().bytes()).isEqualTo(body.bytes());
            assertThat(c.headers()).containsEntry("Idempotency-Key", COMPLETE_KEY.value());
            assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(10));
        });
        assertThat(posts.stream().map(c -> c.headers().get("Request-Id")).distinct()).hasSize(3);
    }

    // Covers: 8.2 (complete that times out is one call and Uncertain; the gateway never re-sends it)
    @Test
    void complete_isOneCallAndUncertain_onTimeout() {
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
        withProfile().on("POST /ucp/v1/checkout-sessions/chk_1001/complete", new HttpTimeoutException("slow"));

        assertThat(gateway().complete(CHK_1001, body, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(transport.callsTo("POST /ucp/v1/checkout-sessions/chk_1001/complete")).hasSize(1);
    }

    // Covers: A.1 (cancel: POST with an empty JSON object and its key)
    @Test
    void cancel_postsAnEmptyObjectUnderItsKey() {
        SessionId chk = new SessionId("chk_1008");
        withProfile().on("POST /ucp/v1/checkout-sessions/chk_1008/cancel", reply(200, "cancel_canceled"));

        CallOutcome<CheckoutView> outcome = gateway().cancel(chk, CANCEL_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CheckoutView) ok.value()).status()).isEqualTo(CheckoutStatus.CANCELED));
        HttpCall post = transport.callsTo("POST /ucp/v1/checkout-sessions/chk_1008/cancel").get(0);
        assertThat(post.body().orElseThrow().json()).isEqualTo("{}");
        assertThat(post.headers()).containsEntry("Idempotency-Key", CANCEL_KEY.value());
    }

    // Covers: 8.4 (get: GET with no key and no body)
    @Test
    void get_sendsNoKeyAndNoBody() {
        withProfile().on("GET /ucp/v1/checkout-sessions/chk_1001", reply(200, "get_ready"));

        assertThat(gateway().get(CHK_1001)).isInstanceOf(CallOutcome.Ok.class);

        HttpCall get = transport.callsTo("GET /ucp/v1/checkout-sessions/chk_1001").get(0);
        assertThat(get.body()).isEmpty();
        assertThat(get.headers()).doesNotContainKeys("Idempotency-Key", "Content-Type");
    }

    // Covers: 8.4 (a session id is percent-encoded as a path segment; it cannot add path segments or a query)
    @Test
    void sessionId_isPercentEncodedInThePath() {
        assertThat(UcpHttpGateway.segment("chk_1001")).isEqualTo("chk_1001");
        assertThat(UcpHttpGateway.segment("a/b?c=d e#f")).isEqualTo("a%2Fb%3Fc%3Dd%20e%23f");
        assertThat(UcpHttpGateway.segment("é")).isEqualTo("%C3%A9");
        withProfile().on("GET /ucp/v1/checkout-sessions/x/../y", reply(404, "error_not_found"));
        // getPath() decodes, so the route above is the decoded form; the assertion below looks at the raw path.
        gateway().get(new SessionId("x/../y"));
        assertThat(transport.calls.get(1).uri().getRawPath()).isEqualTo("/ucp/v1/checkout-sessions/x%2F..%2Fy");
    }

    // Covers: I4 (a reply that is for another session than the one asked for is not trusted)
    @Test
    void reply_forAnotherSession_isNotTrusted() {
        withProfile().on("GET /ucp/v1/checkout-sessions/chk_9999", reply(200, "get_ready"))
                .on("POST /ucp/v1/checkout-sessions/chk_9999/complete", reply(200, "get_ready"))
                .on("POST /ucp/v1/checkout-sessions/chk_9999/cancel", reply(200, "get_ready"))
                .on("PUT /ucp/v1/checkout-sessions/chk_9999", reply(200, "get_ready"));
        UcpHttpGateway g = gateway();
        SessionId other = new SessionId("chk_9999");
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));

        assertThat(g.get(other)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.code()).isEqualTo("invalid_response"));
        assertThat(g.complete(other, body, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(g.cancel(other, CANCEL_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(g.update(other, UpdateCheckout.of(List.of(new ProposedLine("item_123", 1)), Optional.empty()),
                UPDATE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
    }

    // ---------------------------------------------------------------- in-flight intents use the stored profile (19)

    // Covers: 19 (a call for an intent in flight fetches the profile only if there is none, then reuses it whatever its
    // age; only a create refreshes an expired profile, and without it nothing is sent)
    @Test
    void inFlightCalls_reuseTheCachedProfile_andOnlyCreateRefreshes() {
        withProfile().on("GET /ucp/v1/checkout-sessions/chk_1001", reply(200, "get_ready"))
                .on("POST /ucp/v1/checkout-sessions", reply(201, "create_ready"));
        UcpHttpGateway g = gateway();

        g.get(CHK_1001);
        assertThat(transport.callsTo("GET /.well-known/ucp")).hasSize(1);
        clock.advance(Duration.ofHours(1));
        g.get(CHK_1001);
        assertThat(transport.callsTo("GET /.well-known/ucp")).hasSize(1);

        transport.script.get("GET /.well-known/ucp").clear();
        transport.on("GET /.well-known/ucp", new ConnectException("refused"));

        assertThat(g.create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(transport.callsTo("POST /ucp/v1/checkout-sessions")).isEmpty();
        assertThat(g.get(CHK_1001)).isInstanceOf(CallOutcome.Ok.class);
    }

    // ---------------------------------------------------------------- circuit breaker (8.3, decision I)

    // Covers: 8.3, decision I (after five failures create, update, complete and cancel are NotSent without a request,
    // while get, the reconciliation read, still goes out)
    @Test
    void openBreaker_blocksEverythingButGet() {
        withProfile().on("POST /ucp/v1/checkout-sessions", new ConnectException("refused"))
                .on("GET /ucp/v1/checkout-sessions/chk_1001", reply(200, "get_ready"));
        UcpHttpGateway g = gateway(noRetries());
        g.discover(SHOP);
        for (int i = 0; i < 5; i++) {
            assertThat(g.create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        }
        int before = transport.calls.size();
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));

        assertThat(g.create(createRequest(), CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open for 127.0.0.1:8081"));
        assertThat(g.update(CHK_1001, UpdateCheckout.of(List.of(new ProposedLine("item_123", 1)), Optional.empty()),
                UPDATE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.complete(CHK_1001, body, COMPLETE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(g.cancel(CHK_1001, CANCEL_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(transport.calls).hasSize(before);

        assertThat(g.get(CHK_1001)).isInstanceOf(CallOutcome.Ok.class);
        assertThat(transport.calls).hasSize(before + 1);
    }

    // ---------------------------------------------------------------- I8: no credential in any outcome, exception or log

    private static final class Capture extends Handler {
        final List<String> lines = new ArrayList<>();

        @Override
        public void publish(LogRecord r) {
            lines.add(r.getLevel() + " " + r.getMessage() + " " + java.util.Arrays.toString(r.getParameters())
                    + (r.getThrown() == null ? "" : " " + r.getThrown()));
        }

        @Override
        public void flush() {
            // nothing
        }

        @Override
        public void close() {
            // nothing
        }
    }

    // Covers: I8, NFR6 (a merchant or a network error that echoes the payment token or an Authorization value cannot
    // get it into an outcome, an exception message or a log line; the requests never carry Authorization)
    @Test
    void noOutcomeExceptionOrLogLineCarriesTheCredential_orAuthorization() {
        Logger root = Logger.getLogger("");
        Capture capture = new Capture();
        root.addHandler(capture);
        Level before = root.getLevel();
        root.setLevel(Level.ALL);
        try {
            FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", TOKEN));
            String route = "POST /ucp/v1/checkout-sessions/chk_1001/complete";
            String echo = "{\"code\":\"bad_token\",\"content\":\"token " + TOKEN + " rejected, Authorization: Bearer abc.def\"}";
            String okEcho = new String(fixture("complete_declined"), StandardCharsets.UTF_8)
                    .replace("chk_1004", "chk_1001").replace("card_declined", TOKEN);
            List<Object> steps = List.of(
                    new HttpReply(400, Map.of(), echo.getBytes(StandardCharsets.UTF_8)),
                    new IOException("socket said " + TOKEN + " Authorization: Bearer abc.def"),
                    new HttpReply(200, Map.of(), okEcho.getBytes(StandardCharsets.UTF_8)),
                    new HttpReply(200, Map.of(), ("{\"status\":\"" + TOKEN + "\"}").getBytes(StandardCharsets.UTF_8)));
            List<CallOutcome<CheckoutView>> outcomes = new ArrayList<>();
            for (Object step : steps) {
                RoutedTransport t = new RoutedTransport().on("GET /.well-known/ucp", reply(200, "discovery")).on(route, step);
                UcpHttpGateway g = new UcpHttpGateway(MERCHANT, CONFIG, t, breakers, clock, d -> { },
                        new SplittableRandom(1), new SequentialIdGenerator("r"));
                outcomes.add(g.complete(CHK_1001, body, COMPLETE_KEY));
                assertThat(t.calls).allSatisfy(c -> {
                    assertThat(c.headers().keySet()).noneMatch(k -> k.equalsIgnoreCase("Authorization"));
                    assertThat(c.toString()).doesNotContain(TOKEN);
                });
            }

            assertThat(outcomes).hasSize(4);
            for (CallOutcome<CheckoutView> outcome : outcomes) {
                assertThat(outcome.toString()).doesNotContain(TOKEN).doesNotContain("Bearer abc");
                if (outcome instanceof CallOutcome.Ok<CheckoutView> ok) {
                    assertThat(ok.value().toString()).doesNotContain(TOKEN);
                    assertThat(ok.messages().toString()).doesNotContain(TOKEN);
                }
            }
            assertThat(outcomes.get(0)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                    e -> assertThat(e.content()).contains("[redacted]"));
            assertThat(body.toString()).doesNotContain(TOKEN);
            assertThat(capture.lines).noneMatch(l -> l.contains(TOKEN) || l.contains("Bearer"));
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
    }
}
