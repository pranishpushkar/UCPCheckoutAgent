package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.DiscoveryFailedException;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Per-merchant admission and discovery (8.3, 8.5, 19, 20; m6a-plan C4): one breaker and one rate limiter per merchant, the
 * operator's allowlist of endpoint hosts, the payment handler check, and a profile refreshed after a create's 4xx. Two
 * merchants share one host and port here on purpose: a breaker keyed by host and port would be one breaker for both.
 */
class MerchantGatesTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final MerchantEntry ENTRY_A = new MerchantEntry(A, URI.create("http://127.0.0.1:8081/a"), "A",
            MerchantStatus.ACTIVE);
    private static final MerchantEntry ENTRY_B = new MerchantEntry(B, URI.create("http://127.0.0.1:8081/b"), "B",
            MerchantStatus.ACTIVE);
    private static final UcpConfig NO_RETRY = new UcpConfig("profile=\"https://agent.example/.well-known/ucp\"",
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10), 0, Duration.ofMillis(200),
            Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20);
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);

    private static String fixture(String name) {
        try (InputStream in = MerchantGatesTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The lab profile as merchant {@code prefix} serves it: its endpoint under {@code /prefix}, on 127.0.0.1:8081. */
    private static String profile(String prefix) {
        return fixture("discovery").replace("127.0.0.1:8081/ucp/v1", "127.0.0.1:8081/" + prefix + "/ucp/v1");
    }

    private static HttpReply reply(int status, String body, Map<String, List<String>> headers) {
        return new HttpReply(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpReply reply(int status, String body) {
        return reply(status, body, Map.of());
    }

    private static CreateCheckout request() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));
    }

    /** Answers from a function of the call, and records every call. */
    private static final class Transport implements HttpTransport {
        final List<HttpCall> calls = new CopyOnWriteArrayList<>();
        final Function<HttpCall, HttpReply> answer;

        Transport(Function<HttpCall, HttpReply> answer) {
            this.answer = answer;
        }

        List<HttpCall> to(String method, String path) {
            return calls.stream().filter(c -> c.method().equals(method) && c.uri().getPath().equals(path)).toList();
        }

        @Override
        public HttpReply send(HttpCall call) {
            calls.add(call);
            return answer.apply(call);
        }
    }

    private final TestClock clock = new TestClock();
    private final CircuitBreakers gates = new CircuitBreakers(NO_RETRY, clock);

    private UcpHttpGateway gateway(MerchantEntry entry, HttpTransport transport) {
        return new UcpHttpGateway(entry, NO_RETRY, transport, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("r"));
    }

    /** B fails every create with a 503, A answers; both discover their own profile. */
    private Transport twoMerchants(Function<HttpCall, HttpReply> createAtB) {
        return new Transport(call -> {
            String path = call.uri().getPath();
            if (path.equals("/a/.well-known/ucp")) {
                return reply(200, profile("a"));
            }
            if (path.equals("/b/.well-known/ucp")) {
                return reply(200, profile("b"));
            }
            if (path.equals("/a/ucp/v1/checkout-sessions")) {
                return reply(201, fixture("create_incomplete_no_buyer"));
            }
            return createAtB.apply(call);
        });
    }

    // Covers: 8.3, 19, S20 (one breaker per merchant, even for two merchants on one host and port: five failures at B open
    // B's breaker, B's sixth create is not sent, and A's creates still go through on the same host and port)
    @Test
    void breakers_arePerMerchant_evenOnOneHostAndPort() {
        Transport t = twoMerchants(call -> reply(503, "{}"));
        UcpHttpGateway a = gateway(ENTRY_A, t);
        UcpHttpGateway b = gateway(ENTRY_B, t);

        for (int i = 0; i < 5; i++) {
            assertThat(b.create(request(), KEY)).isInstanceOf(CallOutcome.ProtocolError.class);
        }
        CallOutcome<CheckoutView> sixth = b.create(request(), KEY);

        assertThat(sixth).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(t.to("POST", "/b/ucp/v1/checkout-sessions")).as("creates that reached merchant B").hasSize(5);
        assertThat(gates.forMerchant(B).state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gates.forMerchant(A).state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(a.create(request(), KEY)).isInstanceOf(CallOutcome.Ok.class);
        assertThat(t.to("POST", "/a/ucp/v1/checkout-sessions")).hasSize(1);
    }

    // Covers: 8.3, 19 (the gateway and the catalog client of one merchant share its breaker: five failing catalog calls
    // open the breaker that checkout uses, so a create is not sent)
    @Test
    void catalogFailures_openTheBreakerThatCheckoutUses() {
        Transport t = new Transport(call -> {
            String path = call.uri().getPath();
            if (path.equals("/b/.well-known/ucp")) {
                return reply(200, profile("b"));
            }
            return reply(503, "{}");
        });
        UcpHttpGateway b = gateway(ENTRY_B, t);
        CatalogClient catalog = new CatalogClient(b, NO_RETRY, t, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("c"));

        for (int i = 0; i < 5; i++) {
            catalog.search(new dev.ucplab.core.merchant.CatalogSearch("mug", 3));
        }
        CallOutcome<CheckoutView> create = b.create(request(), KEY);

        assertThat(create).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(t.to("POST", "/b/ucp/v1/checkout-sessions")).isEmpty();
    }

    // Covers: 8.3, 19 (a 429 with Retry-After at B blocks B's bucket for every caller, not A's; after that long B is
    // admitted again)
    @Test
    void a429WithRetryAfter_blocksThatMerchantsBucketOnly() {
        Transport t = twoMerchants(call -> reply(429, "{}", Map.of("retry-after", List.of("7"))));
        UcpHttpGateway a = gateway(ENTRY_A, t);
        UcpHttpGateway b = gateway(ENTRY_B, t);

        CallOutcome<CheckoutView> told = b.create(request(), KEY);
        CallOutcome<CheckoutView> next = b.create(request(), KEY);

        assertThat(told).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(429));
        assertThat(next).as("B's next call while blocked").isInstanceOf(CallOutcome.NotSent.class);
        assertThat(t.to("POST", "/b/ucp/v1/checkout-sessions")).hasSize(1);
        assertThat(gates.limiterFor(B).blocked()).isTrue();
        assertThat(gates.limiterFor(A).blocked()).isFalse();
        assertThat(a.create(request(), KEY)).isInstanceOf(CallOutcome.Ok.class);

        clock.advance(Duration.ofSeconds(7));
        assertThat(b.create(request(), KEY)).isInstanceOf(CallOutcome.ProtocolError.class);
        assertThat(t.to("POST", "/b/ucp/v1/checkout-sessions")).hasSize(2);
    }

    // Covers: 8.3 (the token bucket on the injected clock: a burst of 10, then one more per 200 ms at 5 a second, never
    // above the burst; a Retry-After is capped at 30 s)
    @Test
    void rateLimiter_refillsOnTheClock_andCapsRetryAfter() {
        RateLimiter limiter = new RateLimiter(10, 5, clock);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire()).as("token %d of the burst", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
        clock.advance(Duration.ofMillis(199));
        assertThat(limiter.tryAcquire()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.available()).isEqualTo(10);

        limiter.record(new CallOutcome.ProtocolError<>(429, "rate_limited", "", Optional.of(Duration.ofHours(1))));
        clock.advance(Duration.ofSeconds(29));
        assertThat(limiter.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // Covers: 8.3 (a 429 with no Retry-After, and any other outcome, blocks nothing)
    @Test
    void rateLimiter_ignoresOutcomesThatAreNotARetryAfter() {
        RateLimiter limiter = new RateLimiter(2, 1, clock);

        limiter.record(new CallOutcome.ProtocolError<>(429, "rate_limited", "", Optional.empty()));
        limiter.record(new CallOutcome.ProtocolError<>(503, "unavailable", "", Optional.of(Duration.ofSeconds(9))));

        assertThat(limiter.blocked()).isFalse();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // Covers: 8.3, 7.5 (the reconciliation read bypasses both gates: with B's breaker open and its bucket empty, the get is
    // still sent)
    @Test
    void reconciliationGet_bypassesTheBreakerAndTheLimiter() {
        Transport t = new Transport(call -> {
            String path = call.uri().getPath();
            if (path.equals("/b/.well-known/ucp")) {
                return reply(200, profile("b"));
            }
            if (call.method().equals("GET")) {
                return reply(200, fixture("get_ready"));
            }
            return reply(503, "{}");
        });
        UcpHttpGateway b = gateway(ENTRY_B, t);
        for (int i = 0; i < 5; i++) {
            b.create(request(), KEY);
        }
        while (gates.limiterFor(B).tryAcquire()) {
            // empty the bucket
        }
        assertThat(gates.forMerchant(B).state()).isEqualTo(CircuitBreaker.State.OPEN);

        CallOutcome<CheckoutView> read = b.get(new SessionId("chk_1001"));

        assertThat(read).isInstanceOf(CallOutcome.Ok.class);
        assertThat(t.to("GET", "/b/ucp/v1/checkout-sessions/chk_1001")).hasSize(1);
    }

    // Covers: 8.5, 19 (the checkout endpoint must be on the base host and port or one the registry lists: an unlisted host
    // fails discovery as endpoint_not_allowed and no create is sent anywhere)
    @Test
    void anEndpointOnAnUnlistedHost_failsDiscovery_andNoCreateIsSent() {
        String evil = fixture("discovery").replace("127.0.0.1:8081/ucp/v1", "evil.example:8081/ucp/v1");
        Transport t = new Transport(call -> reply(200, evil));
        UcpHttpGateway g = gateway(new MerchantEntry(A, URI.create("http://127.0.0.1:8081"), "A", MerchantStatus.ACTIVE), t);

        assertThatThrownBy(() -> g.discover(A)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining(UcpHttpGateway.ENDPOINT_NOT_ALLOWED);
        assertThat(g.create(request(), KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(t.calls).extracting(c -> c.uri().getHost()).containsOnly("127.0.0.1");
        assertThat(t.calls).filteredOn(c -> c.method().equals("POST")).isEmpty();
    }

    // Covers: 8.5, 19 (a listed second host and port is used: the create goes to it, and the merchant keeps one breaker)
    @Test
    void aListedEndpointHost_isUsed_andSharesTheMerchantsBreaker() {
        String elsewhere = fixture("discovery").replace("127.0.0.1:8081/ucp/v1", "api.shop.example:8443/ucp/v1")
                .replace("http://api.shop.example", "https://api.shop.example");
        Transport t = new Transport(call -> call.uri().getPath().equals("/.well-known/ucp")
                ? reply(200, elsewhere) : reply(201, fixture("create_incomplete_no_buyer")));
        MerchantEntry shop = new MerchantEntry(A, URI.create("https://shop.example"), "A", MerchantStatus.ACTIVE);
        UcpHttpGateway g = new UcpHttpGateway(shop, NO_RETRY, t, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("r"), Set.of("API.shop.example:8443"));

        CallOutcome<CheckoutView> created = g.create(request(), KEY);

        assertThat(created).isInstanceOf(CallOutcome.Ok.class);
        assertThat(t.calls).filteredOn(c -> c.method().equals("POST")).singleElement()
                .satisfies(c -> assertThat(c.uri()).hasHost("api.shop.example").hasPort(8443)
                        .hasPath("/ucp/v1/checkout-sessions"));
        assertThat(gates.forMerchant(A).state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Covers: 8.5 (the scheme must be the base URL's: https base, a profile naming http on a listed host, is refused)
    @Test
    void aSchemeChange_isRefused_evenOnAListedHost() {
        String downgraded = fixture("discovery").replace("127.0.0.1:8081/ucp/v1", "api.shop.example:8443/ucp/v1");
        Transport t = new Transport(call -> reply(200, downgraded));
        MerchantEntry shop = new MerchantEntry(A, URI.create("https://shop.example"), "A", MerchantStatus.ACTIVE);
        UcpHttpGateway g = new UcpHttpGateway(shop, NO_RETRY, t, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("r"), Set.of("api.shop.example:8443"));

        assertThatThrownBy(() -> g.discover(A)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining(UcpHttpGateway.ENDPOINT_NOT_ALLOWED);
    }

    // Covers: 19 (a profile without the payment handler the agent's instrument uses fails discovery as handler_missing)
    @Test
    void aProfileWithoutTheAgentsPaymentHandler_failsAsHandlerMissing() {
        String noHandler = fixture("discovery").replace("mock_pay_1", "other_pay_9");
        Transport t = new Transport(call -> reply(200, noHandler));
        UcpHttpGateway g = gateway(ENTRY_A, t);

        assertThatThrownBy(() -> g.discover(A)).isInstanceOf(DiscoveryFailedException.class)
                .hasMessageContaining(UcpHttpGateway.HANDLER_MISSING);
        assertThat(g.create(request(), KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(t.calls).filteredOn(c -> c.method().equals("POST")).isEmpty();
    }

    // Covers: 20 (a 4xx of a create invalidates the cached profile: the next create fetches a fresh one, so a changed
    // endpoint is picked up; a 5xx does not)
    @Test
    void aCreates4xx_invalidatesTheProfile_aFiveHundredDoesNot() {
        java.util.concurrent.atomic.AtomicBoolean fourXx = new java.util.concurrent.atomic.AtomicBoolean();
        Transport t = new Transport(call -> {
            String path = call.uri().getPath();
            if (path.equals("/a/.well-known/ucp")) {
                return reply(200, profile("a"));
            }
            return reply(fourXx.get() ? 422 : 503, "{}");
        });
        UcpHttpGateway g = gateway(ENTRY_A, t);

        g.create(request(), KEY);        // a 503: the profile stays cached
        g.create(request(), KEY);
        assertThat(t.to("GET", "/a/.well-known/ucp")).as("discoveries after two 503s").hasSize(1);
        fourXx.set(true);
        g.create(request(), KEY);        // a 422: the cache is dropped
        g.create(request(), KEY);

        assertThat(t.to("GET", "/a/.well-known/ucp")).as("discoveries after a 422").hasSize(2);
    }

    // Covers: 7.8 (no lock across I/O: while one thread is inside a stalled create, another reads the breaker's and the
    // limiter's state at once)
    @Test
    void breakerAndLimiterState_areReadable_whileACallIsStalledInTheTransport() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Transport t = new Transport(call -> {
            if (call.uri().getPath().equals("/b/.well-known/ucp")) {
                return reply(200, profile("b"));
            }
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reply(201, fixture("create_incomplete_no_buyer"));
        });
        UcpHttpGateway b = gateway(ENTRY_B, t);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<CallOutcome<CheckoutView>> stalled = pool.submit(() -> b.create(request(), KEY));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            List<Object> seen = new ArrayList<>();
            Future<?> reader = pool.submit(() -> {
                seen.add(gates.forMerchant(B).state());
                seen.add(gates.limiterFor(B).available());
                seen.add(gates.limiterFor(B).blocked());
                seen.add(gates.gateFor(A).tryAcquire());
            });
            reader.get(2, TimeUnit.SECONDS);

            assertThat(seen).hasSize(4);
            release.countDown();
            assertThat(stalled.get(10, TimeUnit.SECONDS)).isInstanceOf(CallOutcome.Ok.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // Covers: 20, H1 C2 (a create answered 429 leaves the cached profile in place: the profile did not change, the
    // merchant is busy; a 422 still drops it)
    @Test
    void aCreates429_keepsTheProfile_a422StillDropsIt() {
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(429);
        Transport t = new Transport(call -> call.uri().getPath().equals("/a/.well-known/ucp")
                ? reply(200, profile("a")) : reply(status.get(), "{}"));
        UcpHttpGateway g = gateway(ENTRY_A, t);

        g.create(request(), KEY);
        g.create(request(), KEY);
        assertThat(t.to("GET", "/a/.well-known/ucp")).as("discoveries after two 429s").hasSize(1);
        status.set(422);
        g.create(request(), KEY);
        g.create(request(), KEY);

        assertThat(t.to("GET", "/a/.well-known/ucp")).as("discoveries after a 422").hasSize(2);
    }

    // Covers: 8.3, H1 C2 (the gate that refuses says why: the limiter rate_limited, the breaker circuit_open, and
    // Gates.all the reason of the gate that refused)
    @Test
    void gatesSayWhyTheyRefused() {
        RateLimiter limiter = new RateLimiter(1, 1, clock);
        CircuitBreaker breaker = new CircuitBreaker(NO_RETRY, clock);

        assertThat(limiter.reason()).isEqualTo("rate_limited");
        assertThat(breaker.reason()).isEqualTo("circuit_open");

        Gate both = Gates.all(limiter, breaker);
        assertThat(both.tryAcquire()).isTrue();
        assertThat(both.tryAcquire()).isFalse();
        assertThat(both.reason()).isEqualTo("rate_limited");
        for (int i = 0; i < 5; i++) {
            breaker.record(new CallOutcome.Uncertain<>("x"));
        }
        clock.advance(Duration.ofSeconds(1));
        assertThat(both.tryAcquire()).isFalse();
        assertThat(both.reason()).isEqualTo("circuit_open");
    }

    // Covers: 8.3, H1 C2 (an empty bucket is reported as rate limiting, not as an open circuit; nothing is sent)
    @Test
    void anEmptyBucket_isReportedAsRateLimited_notCircuitOpen() {
        Transport t = new Transport(call -> reply(200, profile("a")));
        UcpHttpGateway g = gateway(ENTRY_A, t);
        g.discover(A);
        while (gates.limiterFor(A).tryAcquire()) {
            // empty the bucket
        }

        assertThat(g.create(request(), KEY)).isInstanceOfSatisfying(CallOutcome.NotSent.class,
                n -> assertThat(n.cause()).contains("rate limited").doesNotContain("circuit"));
        assertThat(t.calls).filteredOn(c -> c.method().equals("POST")).isEmpty();
    }

    // Covers: 8.5, 19, H1 C2 (an https endpoint host written without a port means 443; an http one without a port is
    // still refused)
    @Test
    void aListedHttpsHostWithoutAPort_means443_httpWithoutAPortIsRefused() {
        String elsewhere = fixture("discovery").replace("127.0.0.1:8081/ucp/v1", "api.shop.example/ucp/v1")
                .replace("http://api.shop.example", "https://api.shop.example");
        Transport t = new Transport(call -> call.uri().getPath().equals("/.well-known/ucp")
                ? reply(200, elsewhere) : reply(201, fixture("create_incomplete_no_buyer")));
        MerchantEntry https = new MerchantEntry(A, URI.create("https://shop.example"), "A", MerchantStatus.ACTIVE);
        UcpHttpGateway g = new UcpHttpGateway(https, NO_RETRY, t, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("r"), Set.of("API.shop.example"));

        assertThat(g.create(request(), KEY)).isInstanceOf(CallOutcome.Ok.class);

        MerchantEntry http = new MerchantEntry(A, URI.create("http://shop.example"), "A", MerchantStatus.ACTIVE);
        assertThatThrownBy(() -> new UcpHttpGateway(http, NO_RETRY, t, gates, clock, d -> { }, new SplittableRandom(1),
                new SequentialIdGenerator("r"), Set.of("api.shop.example"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host:port");
    }
}
