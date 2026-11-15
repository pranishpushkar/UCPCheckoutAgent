package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
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
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** H3 P8: discovery, the choice of endpoint, refusals of complete and the path of a call (8.1, 8.5, 19), per mutant. */
class UcpHttpGatewayMutationTest {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final MerchantEntry MERCHANT = new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop",
            MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey UPDATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.UPDATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final SessionId CHK_1001 = new SessionId("chk_1001");
    private static final String PROFILE = new String(fixture("discovery"), StandardCharsets.UTF_8);

    private static byte[] fixture(String name) {
        try (InputStream in = UcpHttpGatewayMutationTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, String fixture) {
        return new HttpReply(status, Map.of(), fixture(fixture));
    }

    private static HttpReply text(int status, String body, String... headers) {
        Map<String, List<String>> h = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            h.put(headers[i].toLowerCase(), List.of(headers[i + 1]));
        }
        return new HttpReply(status, h, body.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RoutedTransport implements HttpTransport {
        final Map<String, Deque<Object>> script = new HashMap<>();
        final List<HttpCall> calls = new ArrayList<>();

        RoutedTransport on(String route, Object... steps) {
            script.computeIfAbsent(route, k -> new ArrayDeque<>()).addAll(List.of(steps));
            return this;
        }

        RoutedTransport replace(String route, Object... steps) {
            script.remove(route);
            return on(route, steps);
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

    private static ConnectException notSent() {
        ConnectException e = new ConnectException("unresolved");
        e.initCause(new java.nio.channels.UnresolvedAddressException());
        return e;
    }

    private final TestClock clock = new TestClock();
    private final RoutedTransport transport = new RoutedTransport();

    private static UcpConfig noRetries() {
        return new UcpConfig(CONFIG.ucpAgent(), CONFIG.connectTimeout(), CONFIG.requestTimeout(), CONFIG.completeTimeout(), 0,
                CONFIG.backoffInitial(), CONFIG.backoffCap(), CONFIG.breakerThreshold(), CONFIG.breakerOpenFor(),
                CONFIG.maxResponseBytes());
    }

    private UcpHttpGateway gateway() {
        return new UcpHttpGateway(MERCHANT, noRetries(), transport, new CircuitBreakers(noRetries(), clock), clock, d -> { },
                new SplittableRandom(7), new SequentialIdGenerator("req"));
    }

    private UcpHttpGateway gateway(MerchantEntry merchant, Set<String> hosts, Set<String> versions) {
        return new UcpHttpGateway(merchant, noRetries(), transport, new CircuitBreakers(noRetries(), clock), clock, d -> { },
                new SplittableRandom(7), new SequentialIdGenerator("req"), hosts, UcpHttpGateway.PAYMENT_HANDLER, versions);
    }

    private static CreateCheckout createRequest() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
    }

    private static MerchantEntry merchantAt(String url) {
        return new MerchantEntry(SHOP, URI.create(url), "Shop", MerchantStatus.ACTIVE);
    }

    // ---------------------------------------------------------------- discovery

    // Covers: 8.5, I8 (a discovery that fails says why, in words an operator can read). Kills UcpHttpGateway.describe:361
    // EmptyObjectReturnVals (and the branch texts)
    @Test
    void aFailedDiscovery_saysWhy() {
        transport.on("GET /.well-known/ucp", reply(404, "error_not_found"));
        assertThatThrownBy(() -> gateway().discover(SHOP)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessage("discovery failed for shop-1: http 404 not_found");

        RoutedTransport timeout = new RoutedTransport().on("GET /.well-known/ucp", new HttpTimeoutException("timed out"));
        UcpHttpGateway g2 = new UcpHttpGateway(MERCHANT, noRetries(), timeout, new CircuitBreakers(noRetries(), clock), clock,
                d -> { }, new SplittableRandom(1), new SequentialIdGenerator("r"));
        assertThatThrownBy(() -> g2.discover(SHOP)).hasMessage("discovery failed for shop-1: no usable reply: HttpTimeoutException: timed out");

        RoutedTransport dns = new RoutedTransport().on("GET /.well-known/ucp", notSent());
        UcpHttpGateway g3 = new UcpHttpGateway(MERCHANT, noRetries(), dns, new CircuitBreakers(noRetries(), clock), clock,
                d -> { }, new SplittableRandom(1), new SequentialIdGenerator("r"));
        assertThatThrownBy(() -> g3.discover(SHOP)).hasMessage("discovery failed for shop-1: not sent: ConnectException: unresolved");
    }

    // Covers: 8.5, 20 (a profile older than five minutes is not served when it cannot be refreshed: discover fails, a create
    // is NotSent; an in-flight call such as complete still uses the profile it has, 19). Kills UcpHttpGateway.discover:170 and
    // profileFor:231, :233 RemoveConditional (stale profile served after a failed refresh)
    @Test
    void aStaleProfile_isNeverServedAfterAFailedRefresh_exceptToACallInFlight() {
        transport.on("GET /.well-known/ucp", text(200, PROFILE))
                .on("POST /ucp/v1/checkout-sessions/chk_1006/complete", reply(200, "complete_completed"));
        UcpHttpGateway g = gateway();
        assertThat(g.discover(SHOP).ucpVersion()).isEqualTo("2026-08-25");
        clock.advance(Duration.ofMinutes(6));
        transport.replace("GET /.well-known/ucp", reply(500, "error_not_found"));

        assertThatThrownBy(() -> g.discover(SHOP)).isInstanceOf(DiscoveryFailedException.class);
        assertThat(g.create(createRequest(), CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).startsWith("merchant profile unavailable: no usable reply: http 500"));
        assertThat(transport.callsTo("POST /ucp/v1/checkout-sessions")).isEmpty();
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
        assertThat(g.complete(new SessionId("chk_1006"), body, COMPLETE_KEY)).isInstanceOf(CallOutcome.Ok.class);
    }

    // Covers: 26.2 (the operator pins the versions a merchant is spoken to in: a profile that declares another is incompatible).
    // Kills UcpHttpGateway.lambda$negotiate$0:208 RemoveConditional_EQUAL_IF
    @Test
    void aProfileVersion_thatIsNotPinned_isIncompatible() {
        transport.on("GET /.well-known/ucp", text(200, PROFILE));

        assertThatThrownBy(() -> gateway(MERCHANT, Set.of(), Set.of("2026-04-08")).discover(SHOP))
                .hasMessageContaining(UcpHttpGateway.INCOMPATIBLE_VERSION);
        assertThat(gateway(MERCHANT, Set.of(), Set.of("2026-04-08", "2026-08-25")).discover(SHOP).ucpVersion())
                .isEqualTo("2026-08-25");
    }

    // ---------------------------------------------------------------- the allowed endpoint

    private static String profileWithEndpoint(String endpoint) {
        String p = PROFILE.replace("http://127.0.0.1:8081/ucp/v1", endpoint);
        assertThat(p).isNotEqualTo(PROFILE);
        return p;
    }

    private CallOutcome<CheckoutView> createVia(String merchantUrl, String endpoint, Set<String> hosts) {
        RoutedTransport t = new RoutedTransport().on("GET /.well-known/ucp", text(200, profileWithEndpoint(endpoint)))
                .on("POST /ucp/v1/checkout-sessions", reply(201, "create_ready"));
        UcpHttpGateway g = new UcpHttpGateway(merchantAt(merchantUrl), noRetries(), t, new CircuitBreakers(noRetries(), clock),
                clock, d -> { }, new SplittableRandom(7), new SequentialIdGenerator("req"), hosts, UcpHttpGateway.PAYMENT_HANDLER,
                Set.of("2026-08-25"));
        return g.create(createRequest(), CREATE_KEY);
    }

    // Covers: 8.5 (the checkout endpoint must be on the merchant's host or on one the operator lists; the scheme and the port
    // are part of the host, a missing port is the scheme's). Kills UcpHttpGateway.hostPort:394 RemoveConditional_EQUAL_IF and
    // normalizeHostPort:378 (https without a port is 443)
    @Test
    void theEndpointHost_isComparedWithItsPort_aMissingPortBeingTheSchemes() {
        // http, no port: 80
        assertThat(createVia("http://shop.example", "http://other.example/ucp/v1", Set.of("other.example:80"))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(createVia("http://shop.example", "http://other.example/ucp/v1", Set.of("other.example:443"))).isInstanceOf(CallOutcome.NotSent.class);
        // https, no port: 443, for the base URL, the endpoint and a listed host
        assertThat(createVia("https://shop.example", "https://shop.example/ucp/v1", Set.of())).isInstanceOf(CallOutcome.Ok.class);
        assertThat(createVia("https://shop.example", "https://other.example/ucp/v1", Set.of("Other.Example"))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(createVia("https://shop.example", "https://other.example:8443/ucp/v1", Set.of("other.example"))).isInstanceOf(CallOutcome.NotSent.class);
        // an explicit port is compared as it is, and the host is case-insensitive
        assertThat(createVia("http://shop.example:8080", "http://OTHER.example:8443/ucp/v1", Set.of("other.EXAMPLE:8443"))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(createVia("http://shop.example:8080", "http://shop.example:8081/ucp/v1", Set.of())).isInstanceOf(CallOutcome.NotSent.class);
        // the scheme of the endpoint is the merchant's
        assertThat(createVia("http://shop.example", "https://shop.example/ucp/v1", Set.of("shop.example:443"))).isInstanceOf(CallOutcome.NotSent.class);
    }

    // Covers: 8.5 (a listed host is host:port, or a bare host for https only). Kills UcpHttpGateway.normalizeHostPort:378 and
    // :381 (the shapes it refuses)
    @Test
    void aListedEndpointHost_mustBeHostAndPort_orABareHostForHttps() {
        MerchantEntry http = merchantAt("http://shop.example");
        MerchantEntry https = merchantAt("https://shop.example");
        for (String bad : new String[] {"", ":80", "host:", "host", "host:abc", "host:80x"}) {
            assertThatThrownBy(() -> gateway(http, Set.of(bad), Set.of("2026-08-25"))).as("http, '" + bad + "'")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host:port");
        }
        for (String bad : new String[] {"", ":80", "host:"}) {
            assertThatThrownBy(() -> gateway(https, Set.of(bad), Set.of("2026-08-25"))).as("https, '" + bad + "'")
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host:port");
        }
        gateway(https, Set.of("host"), Set.of("2026-08-25"));
        gateway(http, Set.of("h:1"), Set.of("2026-08-25"));
    }

    // Covers: 8.5. Kills UcpHttpGateway.stripTrailingSlash:372 (NO_COVERAGE and RemoveConditional): a base URL with a trailing
    // slash does not double it in the discovery URL
    @Test
    void aBaseUrlWithATrailingSlash_isNotDoubled() {
        transport.on("GET /.well-known/ucp", text(200, PROFILE));
        UcpHttpGateway g = new UcpHttpGateway(merchantAt("http://127.0.0.1:8081/"), noRetries(), transport,
                new CircuitBreakers(noRetries(), clock), clock, d -> { }, new SplittableRandom(7), new SequentialIdGenerator("req"));

        g.discover(SHOP);

        assertThat(transport.calls.get(0).uri()).isEqualTo(URI.create("http://127.0.0.1:8081/.well-known/ucp"));
        UcpHttpGateway plain = new UcpHttpGateway(merchantAt("http://127.0.0.1:8081"), noRetries(), transport,
                new CircuitBreakers(noRetries(), clock), clock, d -> { }, new SplittableRandom(7), new SequentialIdGenerator("req"));
        plain.discover(SHOP);
        assertThat(transport.calls.get(1).uri()).isEqualTo(URI.create("http://127.0.0.1:8081/.well-known/ucp"));
    }

    // ---------------------------------------------------------------- the path of a session

    // Covers: 8.1 (a session id is one path segment: everything but the unreserved characters is percent-encoded, as UTF-8).
    // Kills UcpHttpGateway.segment:402 (every boundary of the four ranges, and the encoded form)
    @Test
    void aSessionIdSegment_keepsOnlyUnreservedCharacters() {
        assertThat(UcpHttpGateway.segment("AZaz09-._~")).isEqualTo("AZaz09-._~");
        assertThat(UcpHttpGateway.segment("@[`{/:!")).isEqualTo("%40%5B%60%7B%2F%3A%21");
        assertThat(UcpHttpGateway.segment("a b")).isEqualTo("a%20b");
        assertThat(UcpHttpGateway.segment("é")).isEqualTo("%C3%A9");
        assertThat(UcpHttpGateway.segment("")).isEmpty();
        assertThat(UcpHttpGateway.segment("\u007f")).isEqualTo("%7F");
        // the neighbours of each range: '/' and ':' around the digits, '@' and '[' around the capitals, '`' and '{' around the lower case
        assertThat(UcpHttpGateway.segment("0")).isEqualTo("0");
        assertThat(UcpHttpGateway.segment("9")).isEqualTo("9");
        assertThat(UcpHttpGateway.segment("A")).isEqualTo("A");
        assertThat(UcpHttpGateway.segment("Z")).isEqualTo("Z");
        assertThat(UcpHttpGateway.segment("a")).isEqualTo("a");
        assertThat(UcpHttpGateway.segment("z")).isEqualTo("z");
    }

    // ---------------------------------------------------------------- the profile is refetched after a 4xx of a create

    private String discoveryRoute() {
        return "GET /.well-known/ucp";
    }

    /** How many times discovery was fetched after one create answered with {@code answer}, and a second create. */
    private int discoveriesAfterACreateAnswering(HttpReply answer) {
        transport.on(discoveryRoute(), text(200, PROFILE)).on("POST /ucp/v1/checkout-sessions", answer);
        UcpHttpGateway g = gateway();
        g.create(createRequest(), CREATE_KEY);
        g.create(createRequest(), CREATE_KEY);
        return transport.callsTo(discoveryRoute()).size();
    }

    // Covers: 20, 8.5 (a 4xx of a create may mean the profile changed: the next create reads a fresh one; a 429 says the merchant
    // is busy, a 5xx or a Retry-After too long to wait is not a change of profile either). Kills UcpHttpGateway.send:299
    // (ConditionalsBoundary, RemoveConditional_EQUAL_IF and _ORDER_IF) and :300 ConditionalsBoundary
    @Test
    void onlyA4xxOtherThan429_makesTheNextCreateReadTheProfileAgain() {
        for (int status : new int[] {400, 401, 403, 404, 409, 422}) {
            assertThat(discoveriesAfterACreateAnswering(reply(status, "error_not_found"))).as("status " + status).isEqualTo(2);
            transport.script.clear();
            transport.calls.clear();
        }
        assertThat(discoveriesAfterACreateAnswering(text(429, "{\"code\":\"busy\",\"content\":\"x\"}")))
                .as("429 (no Retry-After: with one the rate limiter would also keep the second create from reading the profile)").isEqualTo(1);
        transport.script.clear();
        transport.calls.clear();
        assertThat(discoveriesAfterACreateAnswering(text(503, "{\"code\":\"busy\",\"content\":\"x\"}", "Retry-After", "3600")))
                .as("503 with a long Retry-After (a ProtocolError that is not a 4xx)").isEqualTo(1);
    }

    // ---------------------------------------------------------------- refusals of complete (8.1)

    private static String envelope(String code) {
        return "{\"ucp\":{\"status\":\"error\",\"version\":\"2026-08-25\"},\"messages\":[{\"type\":\"error\",\"code\":\""
                + code + "\",\"content\":\"refused: " + code + "\",\"severity\":\"unrecoverable\"}]}";
    }

    private CallOutcome<CheckoutView> completeAnswered(HttpReply answer) {
        transport.on(discoveryRoute(), text(200, PROFILE)).on("POST /ucp/v1/checkout-sessions/chk_1001/complete", answer);
        return gateway().complete(CHK_1001, CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok")), COMPLETE_KEY);
    }

    // Covers: 8.1, 7.2 (the sample merchant's 402 or 409 refusal of complete in an error envelope is the lab's answer: Ok, the
    // session still ready_for_complete, one error message with the lab's code). Kills UcpHttpGateway.refusalAsAnswer:328
    // RemoveConditional_EQUAL_IF (x2) and :330 RemoveConditional_EQUAL_ELSE (x2)
    @Test
    void aDeclineOrAStockRefusalOfComplete_isTheLabsAnswer_withTheLabsCode() {
        record Case(int status, String code, String lab) {}
        for (Case c : List.of(new Case(402, "INSUFFICIENT_FUNDS", "payment_declined"), new Case(402, "payment_failed", "payment_declined"),
                new Case(409, "OUT_OF_STOCK", "out_of_stock"), new Case(409, "insufficient_funds", "payment_declined"),
                new Case(402, "out_of_stock", "out_of_stock"))) {
            transport.script.clear();
            transport.calls.clear();

            CallOutcome<CheckoutView> out = completeAnswered(text(c.status(), envelope(c.code())));

            assertThat(out).as(c.status() + " " + c.code()).isInstanceOfSatisfying(CallOutcome.Ok.class, ok -> {
                assertThat(ok.httpStatus()).isEqualTo(c.status());
                CheckoutView v = (CheckoutView) ok.value();
                assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
                assertThat(v.id()).isEqualTo(CHK_1001);
                assertThat(v.order()).isEmpty();
                assertThat(v.messages()).hasSize(1);
                assertThat(v.messages().get(0).code()).isEqualTo(c.lab());
                assertThat(v.messages().get(0).type()).isEqualTo("error");
                assertThat(v.messages().get(0).content()).isEqualTo("refused: " + c.code());
            });
        }
    }

    // Covers: 8.1, I1 (every other 4xx of complete, a 402 or 409 with a code that is neither a payment nor a stock refusal, and
    // any refusal that is not about complete stays what it was: a ProtocolError, which reconciles). Kills
    // UcpHttpGateway.refusalAsAnswer:328 and :330
    @Test
    void anyOtherRefusal_staysAProtocolError() {
        assertThat(completeAnswered(text(402, envelope("some_other_code")))).isInstanceOf(CallOutcome.ProtocolError.class);
        transport.script.clear();
        assertThat(completeAnswered(text(409, envelope("conflict")))).isInstanceOf(CallOutcome.ProtocolError.class);
        transport.script.clear();
        assertThat(completeAnswered(text(404, envelope("out_of_stock")))).as("404 is not a refusal").isInstanceOf(CallOutcome.ProtocolError.class);
        transport.script.clear();
        assertThat(completeAnswered(text(422, envelope("payment_failed")))).as("422 is not a refusal").isInstanceOf(CallOutcome.ProtocolError.class);

        // an update that is refused the same way is not a refusal of complete
        RoutedTransport t = new RoutedTransport().on(discoveryRoute(), text(200, PROFILE))
                .on("PUT /ucp/v1/checkout-sessions/chk_1001", text(402, envelope("insufficient_funds")));
        UcpHttpGateway g = new UcpHttpGateway(MERCHANT, noRetries(), t, new CircuitBreakers(noRetries(), clock), clock, d -> { },
                new SplittableRandom(7), new SequentialIdGenerator("req"));
        assertThat(g.update(CHK_1001, UpdateCheckout.of(List.of(new ProposedLine("item_123", 1)), Optional.empty()), UPDATE_KEY))
                .isInstanceOf(CallOutcome.ProtocolError.class);
    }

    // Covers: 20 (only a create reads the profile again after a 4xx: a refused complete, update, cancel or get does not). Kills
    // UcpHttpGateway.send:299 RemoveConditional_EQUAL_IF (the `newIntent` operand)
    @Test
    void aRefusedCallInFlight_doesNotMakeTheProfileBeReadAgain() {
        transport.on(discoveryRoute(), text(200, PROFILE))
                .on("POST /ucp/v1/checkout-sessions/chk_1001/complete", reply(404, "error_not_found"))
                .on("PUT /ucp/v1/checkout-sessions/chk_1001", reply(404, "error_not_found"))
                .on("POST /ucp/v1/checkout-sessions/chk_1001/cancel", reply(404, "error_not_found"))
                .on("GET /ucp/v1/checkout-sessions/chk_1001", reply(404, "error_not_found"));
        UcpHttpGateway g = gateway();
        FrozenBody body = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));

        assertThat(g.complete(CHK_1001, body, COMPLETE_KEY)).isInstanceOf(CallOutcome.ProtocolError.class);
        assertThat(g.update(CHK_1001, UpdateCheckout.of(List.of(new ProposedLine("item_123", 1)), Optional.empty()), UPDATE_KEY))
                .isInstanceOf(CallOutcome.ProtocolError.class);
        assertThat(g.cancel(CHK_1001, IdempotencyKey.derive(INTENT, MutatingOp.CANCEL, 0))).isInstanceOf(CallOutcome.ProtocolError.class);
        assertThat(g.get(CHK_1001)).isInstanceOf(CallOutcome.ProtocolError.class);

        assertThat(transport.callsTo(discoveryRoute())).hasSize(1);
    }

    /** Opens the breaker of {@link #SHOP} (five creates the merchant fails with a 500), through a gateway of its own. */
    private CircuitBreakers openedBreakers() {
        CircuitBreakers shared = new CircuitBreakers(noRetries(), clock);
        RoutedTransport t = new RoutedTransport().on(discoveryRoute(), text(200, PROFILE))
                .on("POST /ucp/v1/checkout-sessions", text(500, "{}"));
        UcpHttpGateway opener = new UcpHttpGateway(MERCHANT, noRetries(), t, shared, clock, d -> { }, new SplittableRandom(7),
                new SequentialIdGenerator("req"));
        for (int i = 0; i < noRetries().breakerThreshold(); i++) {
            assertThat(opener.create(createRequest(), CREATE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        }
        return shared;
    }

    // Covers: 8.3, 19 (an open breaker keeps a create from even reading the profile; a call for an intent in flight reads the
    // profile it lacks regardless, as the reads of reconciliation do). Kills UcpHttpGateway.profileFor:231 RemoveConditional
    // (the `bypass the breaker` flag of the refresh)
    @Test
    void anOpenBreaker_blocksTheProfileReadOfACreate_butNotOfACallInFlight() {
        CircuitBreakers breakers = openedBreakers();
        RoutedTransport fresh = new RoutedTransport().on(discoveryRoute(), text(200, PROFILE))
                .on("GET /ucp/v1/checkout-sessions/chk_1001", reply(200, "get_ready"))
                .on("POST /ucp/v1/checkout-sessions", reply(201, "create_ready"));
        UcpHttpGateway g = new UcpHttpGateway(MERCHANT, noRetries(), fresh, breakers, clock, d -> { }, new SplittableRandom(7),
                new SequentialIdGenerator("req"));

        assertThat(g.create(createRequest(), CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("circuit breaker open"));
        assertThat(fresh.calls).as("nothing, not even discovery, left").isEmpty();

        assertThat(g.get(CHK_1001)).isInstanceOf(CallOutcome.Ok.class);
        assertThat(fresh.callsTo(discoveryRoute())).hasSize(1);
    }
}
