package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FaultProxyTest extends ProxyTestBase {

    // Covers: §12 Safety (listens on 127.0.0.1 only)
    @Test
    void start_bindsToLoopbackOnly() throws Exception {
        assertThat(proxy.address().getAddress()).isEqualTo(InetAddress.getByName("127.0.0.1"));
        assertThat(proxy.baseUrl()).isEqualTo(URI.create("http://127.0.0.1:" + proxy.port()));
    }

    // Covers: §12 Safety (the upstream is fixed at start-up and must be a plain loopback http URL)
    @ParameterizedTest
    @ValueSource(strings = {"http://192.0.2.1:8080", "http://10.0.0.1", "http://[2001:db8::1]:80",
            "https://127.0.0.1:8443", "http://127.0.0.1:8080/ucp", "http://user@127.0.0.1:8080",
            "http://127.0.0.1:8080?x=1", "ftp://127.0.0.1", "/relative"})
    void start_refusesAnUpstreamThatIsNotLoopbackHttp(String upstreamUrl) {
        assertThatThrownBy(() -> FaultProxy.start(URI.create(upstreamUrl), 0, KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: §12 Safety (localhost names a loopback address and is accepted)
    @Test
    void start_acceptsLocalhost() throws IOException {
        try (FaultProxy p = FaultProxy.start(URI.create("http://localhost:" + upstream.baseUrl().getPort() + "/"), 0,
                KEY)) {
            assertThat(p.port()).isPositive();
        }
    }

    // Covers: §12 Body hash (the key is exactly 32 bytes)
    @Test
    void start_refusesAKeyThatIsNot32Bytes() {
        assertThatThrownBy(() -> FaultProxy.start(upstream.baseUrl(), 0, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FaultProxy.start(upstream.baseUrl(), 0, new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: §12 Safety, 8.2 (the proxy refuses to start if the JDK may re-send a POST by itself: true or empty)
    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", ""})
    void start_refusesWhenAllMethodRetryIsEnabled(String value) {
        String before = System.getProperty(FaultProxy.ALL_METHOD_RETRY);
        System.setProperty(FaultProxy.ALL_METHOD_RETRY, value);
        try {
            assertThatThrownBy(() -> FaultProxy.start(upstream.baseUrl(), 0, KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(FaultProxy.ALL_METHOD_RETRY);
        } finally {
            if (before == null) {
                System.clearProperty(FaultProxy.ALL_METHOD_RETRY);
            } else {
                System.setProperty(FaultProxy.ALL_METHOD_RETRY, before);
            }
        }
    }

    // Covers: §12 Safety (false is allowed)
    @Test
    void start_allowsAllMethodRetrySetToFalse() throws IOException {
        System.setProperty(FaultProxy.ALL_METHOD_RETRY, "false");
        try (FaultProxy p = FaultProxy.start(upstream.baseUrl(), 0, KEY)) {
            assertThat(p.port()).isPositive();
        } finally {
            System.clearProperty(FaultProxy.ALL_METHOD_RETRY);
        }
    }

    // Covers: §12 (with no rules, a request is forwarded unchanged: method, path, query, body, end-to-end headers)
    @Test
    void forward_relaysTheRequestUnchanged() throws Exception {
        String body = "{\"line_items\":[{\"id\":\"item_123\",\"quantity\":2}]}";

        HttpResponse<String> r = send("POST", "/ucp/v1/checkout-sessions?trace=1", body, Map.of(
                "Content-Type", "application/json", "Idempotency-Key", "ik_1", "Request-Id", "rq_1",
                "UCP-Agent", "profile=\"https://agent.example/profile\""));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstream.arrivals()).hasSize(1);
        EchoUpstream.Arrival a = upstream.arrivals().getFirst();
        assertThat(a.method()).isEqualTo("POST");
        assertThat(a.path()).isEqualTo("/ucp/v1/checkout-sessions");
        assertThat(a.query()).isEqualTo("trace=1");
        assertThat(new String(a.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(a.header("Idempotency-Key")).isEqualTo("ik_1");
        assertThat(a.header("Request-Id")).isEqualTo("rq_1");
        assertThat(a.header("UCP-Agent")).isEqualTo("profile=\"https://agent.example/profile\"");
        assertThat(a.header("Content-Type")).isEqualTo("application/json");
        // Host is the upstream's own: the client's Host is never used for routing or passed on.
        assertThat(a.header("Host")).isEqualTo(upstream.authority());
    }

    // Covers: §12, 8.1 (status and response headers such as Retry-After and Request-Id come back to the client)
    @Test
    void forward_copiesStatusAndResponseHeaders() throws Exception {
        upstream.respondWith((ex, a) -> {
            ex.getResponseHeaders().set("Retry-After", "7");
            ex.getResponseHeaders().set("Request-Id", "rq_merchant");
            EchoUpstream.send(ex, 429, "{\"error\":\"rate_limited\"}");
        });

        HttpResponse<String> r = get("/ucp/v1/checkout-sessions/cs_1");

        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).contains("7");
        assertThat(r.headers().firstValue("Request-Id")).contains("rq_merchant");
        assertThat(r.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(r.body()).isEqualTo("{\"error\":\"rate_limited\"}");
    }

    // Covers: §12 Safety, 8.1 (redirects are never followed: a 3xx goes back to the client as it is)
    @Test
    void forward_neverFollowsRedirects() throws Exception {
        upstream.respondWith((ex, a) -> {
            if (a.path().equals("/start")) {
                ex.getResponseHeaders().set("Location", upstream.baseUrl() + "/elsewhere");
                ex.sendResponseHeaders(302, -1);
            } else {
                EchoUpstream.echo(ex, a);
            }
        });

        HttpResponse<String> r = get("/start");

        assertThat(r.statusCode()).isEqualTo(302);
        assertThat(upstream.count("GET", "/elsewhere")).isZero();
    }

    // Covers: §12 Safety, 8.5 (the discovery body names the proxy's authority, nothing else in it changes)
    @Test
    void forward_rewritesTheDiscoveryProfileToTheProxy() throws Exception {
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, "{\"ucp\":{\"services\":{\"dev.ucp.shopping\":"
                + "{\"rest\":{\"endpoint\":\"http://" + upstream.authority() + "/ucp/v1\"}}}}}"));

        String discovery = get("/.well-known/ucp").body();
        String other = get("/ucp/v1/not-discovery").body();

        assertThat(discovery).contains("\"endpoint\":\"http://" + proxy.baseUrl().getRawAuthority() + "/ucp/v1\"")
                .doesNotContain(upstream.authority());
        assertThat(other).contains(upstream.authority());
    }

    // Covers: §12 Safety (absolute-form targets are refused and the merchant is never contacted)
    @Test
    void route_refusesAbsoluteForm() throws IOException {
        String reply = raw("GET " + upstream.baseUrl() + "/ucp/v1/checkout-sessions HTTP/1.1\r\nHost: x\r\n"
                + "Connection: close\r\n\r\n");

        assertThat(reply).startsWith("HTTP/1.1 400");
        assertThat(upstream.arrivals()).isEmpty();
    }

    // Covers: §12 Safety (CONNECT is refused: the JDK server drops it, and the handler would answer 405)
    @Test
    void route_refusesConnect() throws IOException {
        String reply = raw("CONNECT " + upstream.authority() + " HTTP/1.1\r\nHost: " + upstream.authority()
                + "\r\nConnection: close\r\n\r\n");

        assertThat(reply).doesNotStartWith("HTTP/1.1 2");
        assertThat(upstream.arrivals()).isEmpty();
        assertThat(proxy.log(0)).isEmpty();
    }

    // Covers: §12 Safety (the handler itself refuses CONNECT, should a request with that method reach it)
    @Test
    void route_refusesConnectInOriginForm() throws IOException {
        String reply = raw("CONNECT /ucp/v1 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(reply).doesNotStartWith("HTTP/1.1 2");
        assertThat(upstream.arrivals()).isEmpty();
    }

    // Covers: §12 Safety (/_lab is never reachable through the proxy, in any spelling)
    @ParameterizedTest
    @ValueSource(strings = {"/_lab", "/_lab/orders", "/_lab/reset", "/_LAB/orders", "/%5flab/orders",
            "/%5Flab/orders"})
    void route_labPathsAre404(String path) throws IOException {
        String reply = raw("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(reply).startsWith("HTTP/1.1 404");
        assertThat(upstream.arrivals()).isEmpty();
        assertThat(proxy.log(0)).isEmpty();
    }

    // Covers: §12 Safety (a path the merchant could resolve differently from the proxy is refused)
    @ParameterizedTest
    @ValueSource(strings = {"/ucp/../_lab/orders", "/ucp/./v1", "/ucp/%2e%2e/_lab/orders", "/ucp%2Fv1",
            "/ucp/%5C_lab"})
    void route_refusesDotSegmentsAndEncodedSeparators(String path) throws IOException {
        String reply = raw("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

        assertThat(reply).startsWith("HTTP/1.1 400");
        assertThat(upstream.arrivals()).isEmpty();
    }

    // Covers: §12 Admin API (/_proxy is never forwarded, even on a path the admin API does not know)
    @Test
    void route_proxyPathsAreNeverForwarded() throws Exception {
        assertThat(get("/_proxy/unknown").statusCode()).isEqualTo(404);
        assertThat(get("/_proxy").statusCode()).isEqualTo(404);
        assertThat(upstream.arrivals()).isEmpty();
    }

    // Covers: §12 Admin API, D1 (an entry records seq, at, method, path, requestId, idempotencyKey, bodyHmac,
    // forwarded, forwards, merchantStatus, action, ruleId, upstreamError, clientGone)
    @Test
    void log_recordsAForwardedRequest() throws Exception {
        String body = "{\"a\":1}";
        send("POST", "/ucp/v1/checkout-sessions", body, Map.of("Idempotency-Key", "ik_7", "Request-Id", "rq_7",
                "Content-Type", "application/json"));
        get("/ucp/v1/checkout-sessions/cs_1");

        List<LogEntry> log = proxy.log(0);

        assertThat(log).hasSize(2);
        LogEntry post = log.get(0);
        assertThat(post.seq()).isEqualTo(1);
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.path()).isEqualTo("/ucp/v1/checkout-sessions");
        assertThat(post.requestId()).contains("rq_7");
        assertThat(post.idempotencyKey()).contains("ik_7");
        assertThat(post.bodyHmac()).contains(hmac(body));
        assertThat(post.forwarded()).isTrue();
        assertThat(post.forwards()).isEqualTo(1);
        assertThat(post.merchantStatus()).hasValue(200);
        assertThat(post.action()).isEmpty();
        assertThat(post.ruleId()).isEmpty();
        assertThat(post.upstreamError()).isFalse();
        assertThat(post.clientGone()).isFalse();
        LogEntry getEntry = log.get(1);
        assertThat(getEntry.seq()).isEqualTo(2);
        assertThat(getEntry.bodyHmac()).isEmpty();
        assertThat(getEntry.idempotencyKey()).isEmpty();
        assertThat(getEntry.at()).isAfterOrEqualTo(post.at());
    }

    // Covers: §12 Admin API (the JSON form carries exactly the contract's fields, absent values as null)
    @Test
    void adminLog_servesTheContractFields() throws Exception {
        get("/ucp/v1/checkout-sessions/cs_1");

        Object parsed = Json.read(adminLog());

        assertThat(parsed).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) ((List<?>) parsed).getFirst();
        assertThat(entry.keySet()).containsExactly("seq", "at", "method", "path", "requestId", "idempotencyKey",
                "bodyHmac", "forwarded", "forwards", "merchantStatus", "action", "ruleId", "upstreamError",
                "clientGone");
        assertThat(entry.get("seq")).isEqualTo(1L);
        assertThat(entry.get("bodyHmac")).isEqualTo(Json.NULL);
        assertThat(entry.get("merchantStatus")).isEqualTo(200L);
        assertThat(entry.get("forwarded")).isEqualTo(true);
        assertThat(entry.get("action")).isEqualTo(Json.NULL);
        assertThat(java.time.Instant.parse((String) entry.get("at"))).isNotNull();
    }

    // Covers: §12 Admin API (?after=<seq> returns only later entries; any other query is refused)
    @Test
    void adminLog_afterFiltersBySeq() throws Exception {
        for (int i = 0; i < 5; i++) {
            get("/ucp/v1/x" + i);
        }

        List<?> after3 = (List<?>) Json.read(get("/_proxy/log?after=3").body());

        assertThat(after3).hasSize(2);
        assertThat(((Map<?, ?>) after3.get(0)).get("seq")).isEqualTo(4L);
        assertThat(Json.read(get("/_proxy/log?after=5").body())).isEqualTo(List.of());
        assertThat(get("/_proxy/log?after=-1").statusCode()).isEqualTo(400);
        assertThat(get("/_proxy/log?after=x").statusCode()).isEqualTo(400);
        assertThat(get("/_proxy/log?since=1").statusCode()).isEqualTo(400);
        assertThat(proxy.log(3)).extracting(LogEntry::seq).containsExactly(4L, 5L);
    }

    // Covers: §12 Admin API, 7.8 (24 concurrent requests get 24 distinct, contiguous seq numbers)
    @Test
    void log_concurrentRequestsGetContiguousSeq() throws Exception {
        int n = 24;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                String body = "{\"i\":" + i + "}";
                futures.add(pool.submit(() -> {
                    go.await();
                    return post("/ucp/v1/checkout-sessions/cs_1/complete", body).statusCode();
                }));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(200);
            }
        }

        List<LogEntry> log = proxy.log(0);
        assertThat(log).extracting(LogEntry::seq)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, n).boxed().toList());
        assertThat(log).extracting(e -> e.bodyHmac().orElseThrow()).doesNotHaveDuplicates();
        assertThat(log).allSatisfy(e -> assertThat(e.forwards()).isEqualTo(1));
        assertThat(upstream.arrivals()).hasSize(n);
    }

    // Covers: §12 Admin API (POST replaces the rules, DELETE clears them; an invalid set is refused and changes nothing)
    @Test
    void adminRules_replaceAndClear() throws Exception {
        String valid = "{\"seed\":1,\"rules\":[{\"id\":\"r\",\"match\":{},\"action\":{\"type\":\"delay\",\"ms\":0}}]}";

        assertThat(post("/_proxy/rules", valid).statusCode()).isEqualTo(204);
        HttpResponse<String> bad = post("/_proxy/rules", "{\"seed\":1,\"rules\":[{\"id\":\"r\"}]}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("invalid_rules");
        assertThat(post("/_proxy/rules", "not json").statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/_proxy/rules", "", Map.of()).statusCode()).isEqualTo(204);
        assertThat(get("/_proxy/rules").statusCode()).isEqualTo(405);
        assertThat(send("PUT", "/_proxy/log", "{}", Map.of()).statusCode()).isEqualTo(405);
        assertThat(upstream.arrivals()).isEmpty();
        assertThat(proxy.log(0)).isEmpty();
    }

    // Covers: §12 Body hash, Decision 2 (the admin API has no route that reads or sets the key, and a key cannot
    // ride in on a rule set)
    @Test
    void adminApi_hasNoRouteForTheKey() throws Exception {
        assertThat(AdminApi.ROUTES).containsExactlyInAnyOrder("/_proxy/rules", "/_proxy/log");
        for (String path : List.of("/_proxy/key", "/_proxy/hmac", "/_proxy/hmacKey", "/_proxy/hmac-key",
                "/_proxy/config", "/_proxy/secret", "/_proxy/rules/key", "/_proxy/log/key")) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                HttpResponse<String> r = send(method, path, method.equals("GET") || method.equals("DELETE") ? ""
                        : "{\"key\":\"" + KEY_HEX + "\"}", Map.of());
                assertThat(r.statusCode()).as(method + " " + path).isEqualTo(404);
            }
        }
        HttpResponse<String> withKey = post("/_proxy/rules", "{\"seed\":1,\"rules\":[],\"hmacKey\":\"" + KEY_HEX + "\"}");
        assertThat(withKey.statusCode()).isEqualTo(400);
        assertThat(withKey.body()).doesNotContain(KEY_HEX);
        assertThat(post("/_proxy/rules?key=" + KEY_HEX, "{\"seed\":1,\"rules\":[]}").statusCode()).isEqualTo(400);
        assertThat(adminLog()).doesNotContain(KEY_HEX);
        // No public method hands the key out.
        assertThat(FaultProxy.class.getMethods()).noneSatisfy(m -> assertThat(
                m.getReturnType() == byte[].class || java.security.Key.class.isAssignableFrom(m.getReturnType())
                        || m.getName().toLowerCase().contains("key")).isTrue());
    }

    // Covers: §12 Body hash, I8 (no log entry holds the payment token, the Authorization header, the body, or a plain
    // SHA-256 of the body; the bodyHmac is the keyed hash)
    @Test
    void log_neverHoldsTheTokenOrTheBody() throws Exception {
        String token = "tok_visa_4242_7f3a9c";
        String complete = "{\"payment\":{\"instruments\":[{\"id\":\"pi_1\",\"handler_id\":\"mock_card\","
                + "\"type\":\"card\",\"credential\":{\"type\":\"token\",\"token\":\"" + token + "\"}}],"
                + "\"selected_instrument_id\":\"pi_1\"},\"risk_signals\":{}}";

        HttpResponse<String> r = send("POST", "/ucp/v1/checkout-sessions/cs_1/complete", complete, Map.of(
                "Content-Type", "application/json", "Idempotency-Key", "ik_c", "Request-Id", "rq_c",
                "Authorization", "Bearer sk_live_secret_value"));

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstream.arrivals().getFirst().header("Authorization")).isEqualTo("Bearer sk_live_secret_value");
        String log = adminLog();
        assertThat(log).doesNotContain(token).doesNotContain("tok_").doesNotContain("credential")
                .doesNotContain("sk_live_secret_value").doesNotContain("Bearer")
                .doesNotContain(sha256(complete)).doesNotContain(KEY_HEX)
                .contains(hmac(complete)).contains("ik_c").contains("rq_c");
        assertThat(proxy.log(0).toString()).doesNotContain(token).doesNotContain(KEY_HEX);
    }

    // Covers: §12, 8.1 (when the merchant cannot be reached, the client connection is closed with no reply, so the
    // outcome stays Uncertain; the entry says upstreamError)
    @Test
    void forward_unreachableUpstream_closesWithNoReply() throws Exception {
        upstream.close();

        assertThatThrownBy(() -> post("/ucp/v1/checkout-sessions", "{}")).isInstanceOf(IOException.class);

        LogEntry e = proxy.log(0).getFirst();
        assertThat(e.upstreamError()).isTrue();
        assertThat(e.merchantStatus()).isEmpty();
    }

    // Covers: §12 Safety (a merchant reply over 4 MB is dropped as an upstream error)
    @Test
    void forward_oversizedReply_isAnUpstreamError() throws Exception {
        upstream.respondWith((ex, a) -> EchoUpstream.send(ex, 200, "x".repeat(FaultProxy.MAX_BODY_BYTES + 1)));

        assertThatThrownBy(() -> post("/ucp/v1/checkout-sessions", "{}")).isInstanceOf(IOException.class);

        assertThat(proxy.log(0).getFirst().upstreamError()).isTrue();
    }

    // Covers: §12 (an empty reply body, such as a 204, is relayed without a body)
    @Test
    void forward_relaysAnEmptyReply() throws Exception {
        upstream.respondWith((ex, a) -> ex.sendResponseHeaders(204, -1));

        HttpResponse<String> r = send("DELETE", "/ucp/v1/thing", "", Map.of());

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
    }
}
