package dev.ucplab.ucp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** The token provider (20, 26.4, I8, H5 C5). */
class TokenProviderTest {

    private static final URI ENDPOINT = URI.create("https://api.shopify.example/auth/access_token");
    private static final String SECRET = "CANARY-secret-5b9c";
    private static final ClientCredentials CREDENTIALS = new ClientCredentials("CANARY-client-id-2d1e", SECRET);
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(30);
    private static final Duration MARGIN = Duration.ofSeconds(60);

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-16T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    /** Answers each call from {@code answers}; optionally holds each call until {@link #gate} opens. */
    static final class Endpoint implements HttpTransport {
        final List<HttpCall> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger issued = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch gate = new CountDownLatch(0);
        volatile Supplier<Object> answer = () -> token("CANARY-token-" + issued.incrementAndGet(), 3600);

        @Override
        public HttpReply send(HttpCall call) throws IOException {
            calls.add(call);
            entered.countDown();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
            Object a = answer.get();
            if (a instanceof IOException e) {
                throw e;
            }
            return (HttpReply) a;
        }
    }

    static HttpReply token(String value, long expiresIn) {
        return reply(200, "{\"access_token\":\"" + value + "\",\"expires_in\":" + expiresIn + ",\"scope\":\"x\"}");
    }

    static HttpReply reply(int status, String body) {
        return new HttpReply(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private final TestClock clock = new TestClock();
    private final Endpoint endpoint = new Endpoint();

    private TokenProvider provider() {
        return new TokenProvider(ENDPOINT, CREDENTIALS, endpoint, clock, DEFAULT_LIFETIME, MARGIN, Duration.ofSeconds(5));
    }

    private static AccessToken ok(TokenResult r) {
        assertThat(r).isInstanceOf(TokenResult.Ok.class);
        return ((TokenResult.Ok) r).token();
    }

    // Covers: 26.4, 20 (client_credentials as JSON to the configured endpoint; the token is held, not fetched again)
    @Test
    void current_postsClientCredentials_andHoldsTheToken() {
        TokenProvider p = provider();

        AccessToken first = ok(p.current());
        AccessToken again = ok(p.current());

        assertThat(again).isSameAs(first);
        assertThat(first.authorizationHeader()).isEqualTo("Bearer CANARY-token-1");
        assertThat(endpoint.calls).hasSize(1);
        HttpCall call = endpoint.calls.get(0);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri()).isEqualTo(ENDPOINT);
        assertThat(CanonicalJson.readObject(call.body().orElseThrow().json())).isEqualTo(Map.of(
                "client_id", CREDENTIALS.clientId(), "client_secret", SECRET, "grant_type", "client_credentials"));
    }

    // Covers: 20, VERIFY 1 (expires_in sets the end; the token is refreshed refreshMargin before it, not after)
    @Test
    void current_refreshesBeforeTheTokenEnds() {
        TokenProvider p = provider();
        AccessToken first = ok(p.current());
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-11-16T11:00:00Z"));

        clock.advance(Duration.ofSeconds(3600 - 61));
        assertThat(ok(p.current())).isSameAs(first);
        clock.advance(Duration.ofSeconds(1));
        AccessToken second = ok(p.current());

        assertThat(second).isNotSameAs(first);
        assertThat(endpoint.calls).hasSize(2);
    }

    // Covers: VERIFY 1 (no expires_in: the configured default lifetime; TODO(VERIFY 1) in TokenProvider)
    @Test
    void current_withoutExpiresIn_usesTheDefaultLifetime() {
        endpoint.answer = () -> reply(200, "{\"access_token\":\"CANARY-token-x\"}");

        assertThat(ok(provider().current()).expiresAt()).isEqualTo(clock.now.plus(DEFAULT_LIFETIME));
    }

    // Covers: 20 (a short-lived token is refreshed at half its life at the latest, never after it ends)
    @Test
    void current_shortLifetime_refreshesAtHalfItsLife() {
        endpoint.answer = () -> token("CANARY-token-" + endpoint.issued.incrementAndGet(), 40);
        TokenProvider p = provider();
        AccessToken first = ok(p.current());

        clock.advance(Duration.ofSeconds(19));
        assertThat(ok(p.current())).isSameAs(first);
        clock.advance(Duration.ofSeconds(1));
        assertThat(ok(p.current())).isNotSameAs(first);
    }

    /**
     * A clock whose first {@code parties} reads wait for each other: {@link TokenProvider#current} reads the clock after
     * reading its state and before its compare-and-set, so every caller acts on the same state at once. Deterministic,
     * with no hook in the provider.
     */
    static final class MeetingClock extends Clock {
        private final java.util.concurrent.CyclicBarrier meet;
        private final AtomicInteger reads = new AtomicInteger();
        private final Instant now = Instant.parse("2026-11-16T10:00:00Z");

        MeetingClock(int parties) {
            meet = new java.util.concurrent.CyclicBarrier(parties);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (reads.incrementAndGet() <= meet.getParties()) {
                try {
                    meet.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("callers did not meet", e);
                }
            }
            return now;
        }
    }

    // Covers: 20, 7.8 (one refresh at a time: callers with no token that all read the same state at the same moment
    // make one fetch between them, and all get its token)
    @Test
    void concurrentCallers_withNoToken_shareOneFetch() throws Exception {
        TokenProvider p = new TokenProvider(ENDPOINT, CREDENTIALS, endpoint, new MeetingClock(8), DEFAULT_LIFETIME,
                MARGIN, Duration.ofSeconds(5));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<TokenResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(p::current));
            }

            List<AccessToken> tokens = new ArrayList<>();
            for (Future<TokenResult> f : results) {
                tokens.add(ok(f.get(5, TimeUnit.SECONDS)));
            }
            assertThat(endpoint.calls).hasSize(1);
            assertThat(tokens).allSatisfy(t -> assertThat(t).isSameAs(tokens.get(0)));
        } finally {
            pool.shutdownNow();
        }
    }

    // Covers: 20, 7.8 (while one caller refreshes a token that is due but not ended, the others use it at once and do
    // not wait on the network)
    @Test
    void whileOneRefreshes_othersUseTheCurrentToken_withoutWaiting() throws Exception {
        TokenProvider p = provider();
        AccessToken first = ok(p.current());
        clock.advance(Duration.ofSeconds(3600 - 30));   // due, not ended
        CountDownLatch gate = new CountDownLatch(1);
        endpoint.gate = gate;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TokenResult> refresher = pool.submit(p::current);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);   // bounded: a red must fail, not hang
            while (endpoint.calls.size() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(endpoint.calls).as("the due token's refresh has started").hasSize(2);

            for (int i = 0; i < 5; i++) {
                assertThat(ok(p.current())).isSameAs(first);
            }
            gate.countDown();

            assertThat(ok(refresher.get(5, TimeUnit.SECONDS))).isNotSameAs(first);
            assertThat(endpoint.calls).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    // Covers: 20, 8.2 (a 401 drops the refused token so the next caller fetches; a token already replaced is left)
    @Test
    void invalidate_dropsOnlyTheRefusedToken() {
        TokenProvider p = provider();
        AccessToken first = ok(p.current());

        p.invalidate(first);
        AccessToken second = ok(p.current());
        p.invalidate(first);

        assertThat(second).isNotSameAs(first);
        assertThat(ok(p.current())).isSameAs(second);
        assertThat(endpoint.calls).hasSize(2);
    }

    // Covers: 26.4, I8 (no token: a fixed reason that names the status only, never the body)
    @Test
    void failures_areFixedReasons() {
        for (Object answer : List.<Object>of(reply(401, "{\"error\":\"" + SECRET + "\"}"), reply(500, SECRET),
                reply(200, "not json"), reply(200, "{\"token\":\"x\"}"), reply(200, "{\"access_token\":\"\"}"),
                new IOException("refused " + SECRET))) {
            endpoint.answer = () -> answer;
            TokenResult r = new TokenProvider(ENDPOINT, CREDENTIALS, endpoint, clock, DEFAULT_LIFETIME, MARGIN,
                    Duration.ofSeconds(5)).current();
            assertThat(r).isInstanceOfSatisfying(TokenResult.Failed.class, f -> assertThat(f.reason())
                    .isIn("token endpoint http 401", "token endpoint http 500", TokenProvider.BAD_ANSWER,
                            TokenProvider.UNREACHABLE));
        }
    }

    // Covers: 20 (a failed refresh keeps a token that has not ended; one that has ended is not used)
    @Test
    void failedRefresh_keepsATokenThatHasNotEnded() {
        TokenProvider p = provider();
        AccessToken first = ok(p.current());
        endpoint.answer = () -> reply(503, "");

        clock.advance(Duration.ofSeconds(3600 - 30));
        assertThat(ok(p.current())).isSameAs(first);
        clock.advance(Duration.ofSeconds(30));
        assertThat(p.current()).isInstanceOf(TokenResult.Failed.class);
    }

    // Covers: I8, 26.4, 20 (the client id, the secret and the token reach no log, stream, toString or reason, on any
    // path, failures that echo the secret included)
    @Test
    void secretsAndTokens_reachNoLogStreamOrToString() {
        List<String> seen = new ArrayList<>();
        Logger root = Logger.getLogger("");
        Level before = root.getLevel();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.add(r.getMessage() + " " + r.getThrown());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ByteArrayOutputStream streams = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        root.addHandler(capture);
        root.setLevel(Level.ALL);
        System.setOut(new PrintStream(streams, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(streams, true, StandardCharsets.UTF_8));
        try {
            TokenProvider p = provider();
            TokenResult r = p.current();
            AccessToken t = ok(r);
            seen.addAll(List.of(p.toString(), CREDENTIALS.toString(), t.toString(), r.toString(),
                    endpoint.calls.get(0).toString()));
            for (Object answer : List.<Object>of(reply(401, SECRET), reply(200, "{\"access_token\":7,\"x\":\"" + SECRET
                    + "\"}"), new IOException(SECRET))) {
                endpoint.answer = () -> answer;
                TokenResult failed = provider().current();
                seen.add(failed.toString());
            }
        } finally {
            System.setOut(out);
            System.setErr(err);
            root.removeHandler(capture);
            root.setLevel(before);
        }
        seen.add(streams.toString(StandardCharsets.UTF_8));

        String all = String.join("\n", seen);
        assertThat(all).doesNotContainIgnoringCase("CANARY");
        assertThat(seen).hasSizeGreaterThan(5);
    }
}
