package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.config.ServiceProperties;
import dev.ucplab.service.lab.LabControls;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lab controls (23.3, 24.3, m6-plan section 6, C15) in the {@code local} profile, against the reference merchant
 * behind the real fault proxy, and against a recording stub where the exact rules are the point.
 */
class LabApiTest {

    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;
    private HttpServer stub;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        if (stub != null) {
            stub.stop(0);
        }
    }

    private void start(Optional<Clock> clock) throws IOException {
        service = TestService.start(dir, Map.of(), clock);
        api = new ApiClient(service.port());
    }

    private IntentId propose(String user, String item) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, ServiceProperties.MERCHANT,
                List.of(new ProposedLine(item, 1)), "lab test");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        sleep(5);
        return result.intentId();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String token(Session s, IntentId id) {
        return (String) json(api.get(s, "/api/intents/" + id.value() + "/approval")).get("token");
    }

    private HttpResponse<String> approve(Session s, IntentId id, String token) {
        return api.post(s, "/api/intents/" + id.value() + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}");
    }

    private Map<String, Object> intent(Session s, IntentId id) {
        return json(api.get(s, "/api/intents/" + id.value()));
    }

    private Map<String, Object> awaitIntent(Session s, IntentId id, Predicate<Map<String, Object>> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> now = intent(s, id);
        while (!done.test(now) && System.nanoTime() < deadline) {
            sleep(25);
            now = intent(s, id);
        }
        return now;
    }

    /** A GET straight through the proxy, which the lab's rules act on. */
    private int viaProxy(String path) {
        try {
            HttpResponse<Void> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(service.proxy.baseUrl().resolve(path)).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // Covers: 23.3, 18.5 (every lab route needs a session: 401 without one, in local)
    @Test
    void labRoutes_withoutASession_are401() throws IOException {
        start(Optional.empty());

        for (String[] r : new String[][] {{"PUT", "/api/lab/faults"}, {"DELETE", "/api/lab/faults"},
                {"GET", "/api/lab/orders?merchant_id=merchant-a"}, {"POST", "/api/lab/reset"}}) {
            HttpResponse<String> response = api.send(r[0], r[1], Optional.of("{}"), "Content-Type", "application/json");
            assertThat(response.statusCode()).as(r[0] + " " + r[1]).isEqualTo(401);
        }
    }

    // Covers: 23.3, 24.3, S2 (the drop preset, end to end: the next complete is forwarded and its answer dropped, the
    // proxy logs that action, and the purchase still ends COMPLETED with exactly one order)
    @Test
    void dropPreset_endToEnd_oneOrder() throws IOException {
        start(Optional.empty());
        Session alice = api.signIn("alice");
        IntentId id = propose("alice", "item_456");

        HttpResponse<String> put = api.send("PUT", "/api/lab/faults", Optional.of("{\"preset\":\"drop_next_complete\"}"),
                "Cookie", "ucplab_session=" + alice.cookie(), "X-CSRF-Token", alice.csrf(), "Content-Type",
                "application/json");
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(json(put)).containsEntry("rule_ids", List.of("lab-drop-next-complete"));
        assertThat(approve(alice, id, token(alice, id)).statusCode()).isEqualTo(200);

        Map<String, Object> done = awaitIntent(alice, id, i -> "COMPLETED".equals(i.get("state")));

        assertThat(done).containsEntry("state", "COMPLETED");
        assertThat(service.merchant.orders()).hasSize(1);
        assertThat(service.proxy.log(0)).extracting(e -> e.action().orElse("")).contains("forward_then_drop_response");
        assertThat(json(api.get(alice, "/api/lab/orders?merchant_id=merchant-a")).get("orders"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    // Covers: 23.3, 24.3 (each preset installs exactly its rules, as the proxy's section 12 format, and clears them on
    // DELETE; read from what the proxy was sent)
    @Test
    void eachPreset_installsItsRules() throws IOException {
        List<String> sent = new CopyOnWriteArrayList<>();
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            sent.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        stub.start();
        service = TestService.start(dir, Map.of("MERCHANT_URL", "http://127.0.0.1:" + stub.getAddress().getPort()),
                Optional.empty());
        api = new ApiClient(service.port());
        Session alice = api.signIn("alice");

        for (String preset : List.of("drop_next_complete", "merchant_503_20s", "merchant_down")) {
            assertThat(put(alice, "{\"preset\":\"" + preset + "\"}").statusCode()).as(preset).isEqualTo(200);
        }
        HttpResponse<String> cleared = api.send("DELETE", "/api/lab/faults", Optional.empty(), "Cookie",
                "ucplab_session=" + alice.cookie(), "X-CSRF-Token", alice.csrf());

        assertThat(cleared.statusCode()).isEqualTo(204);
        assertThat(sent).hasSize(4);
        assertRules(sent.get(0), Map.of("id", "lab-drop-next-complete", "times", 1L,
                "match", Map.of("method", "POST", "pathPattern", "/ucp/v1/checkout-sessions/*/complete"),
                "action", Map.of("type", "forward_then_drop_response")));
        assertRules(sent.get(1), Map.of("id", "lab-merchant-503", "match", Map.of(),
                "action", Map.of("type", "fail_before_forward", "status", 503L, "retryAfterSeconds", 1L)));
        assertRules(sent.get(2), Map.of("id", "lab-merchant-down", "match", Map.of(),
                "action", Map.of("type", "fail_before_forward", "close", true)));
        assertThat(sent.get(3)).startsWith("DELETE /_proxy/rules");
    }

    private HttpResponse<String> put(Session s, String body) {
        return api.send("PUT", "/api/lab/faults", Optional.of(body), "Cookie", "ucplab_session=" + s.cookie(),
                "X-CSRF-Token", s.csrf(), "Content-Type", "application/json");
    }

    private static void assertRules(String request, Map<String, Object> expectedRule) {
        assertThat(request).startsWith("POST /_proxy/rules ");
        Map<String, Object> set = LenientJson.readObject(request.substring("POST /_proxy/rules ".length()));
        assertThat(set).containsEntry("seed", 0L);
        assertThat(((List<?>) set.get("rules")).toArray()).containsExactly(expectedRule);
    }

    // Covers: 23.3, 24.3 (the 503 window: the merchant answers 503 through the proxy, still at 19 s, and is clear at 20 s,
    // on a fake clock; a rule set the caller installs meanwhile is not removed by the window's end)
    @Test
    void merchant503Window_clearsAtTwentySeconds_andNotBefore() throws IOException {
        FakeClock clock = new FakeClock(T0);
        start(Optional.of(clock));
        Session alice = api.signIn("alice");
        LabControls controls = service.bean(LabControls.class);

        assertThat(put(alice, "{\"preset\":\"merchant_503_20s\"}").statusCode()).isEqualTo(200);
        assertThat(viaProxy("/.well-known/ucp")).isEqualTo(503);
        clock.advance(Duration.ofSeconds(19));
        controls.sweep();
        assertThat(viaProxy("/.well-known/ucp")).as("19 s: still failing").isEqualTo(503);
        clock.advance(Duration.ofSeconds(1));
        controls.sweep();
        assertThat(viaProxy("/.well-known/ucp")).as("20 s: cleared").isEqualTo(200);

        assertThat(put(alice, "{\"preset\":\"merchant_503_20s\"}").statusCode()).isEqualTo(200);
        String own = "{\"rules\":{\"seed\":1,\"rules\":[{\"id\":\"mine\",\"match\":{\"pathPattern\":\"/.well-known/ucp\"},"
                + "\"action\":{\"type\":\"fail_before_forward\",\"status\":500}}]}}";
        HttpResponse<String> mine = put(alice, own);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(json(mine)).containsEntry("rule_ids", List.of("mine"));
        clock.advance(Duration.ofSeconds(25));
        controls.sweep();
        assertThat(viaProxy("/.well-known/ucp")).as("the caller's own rules survive the window").isEqualTo(500);
    }

    // Covers: 23.3 (a rule set the proxy refuses is 422 with a fixed message and no proxy detail; an unknown preset or a
    // body with neither is 422 too)
    @Test
    void badFaults_are422() throws IOException {
        start(Optional.empty());
        Session alice = api.signIn("alice");

        for (String body : List.of("{\"preset\":\"explode\"}", "{}", "{\"rules\":{\"seed\":1,\"rules\":[{\"id\":5}]}}")) {
            HttpResponse<String> r = put(alice, body);
            assertThat(r.statusCode()).as(body).isEqualTo(422);
            assertThat(json(r)).containsEntry("code", "validation_failed");
            assertThat(r.body()).doesNotContain("explode").doesNotContain("invalid_rules");
        }
    }

    // Covers: 23.3, 24.3, P7, T2, T6 (change price raises the price, at the merchant, of the item of the caller's newest
    // AWAITING_APPROVAL intent by 1: alice's newest, even when bob has a newer one, and nobody else's item. The approve
    // then re-quotes and asks again, and bob's intent is untouched)
    @Test
    void changePrice_raisesTheCallersNewestIntentsItem_only() throws IOException {
        start(Optional.empty());
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        IntentId older = propose("alice", "item_123");
        IntentId newest = propose("alice", "item_456");
        IntentId bobs = propose("bob", "item_789");
        String oldToken = token(alice, newest);
        String bobsToken = token(bob, bobs);

        HttpResponse<String> r = put(alice, "{\"preset\":\"change_price\"}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r)).containsEntry("rule_ids", List.of());
        assertThat(service.merchant.price("item_456")).isEqualTo(1201);
        assertThat(service.merchant.price("item_123")).isEqualTo(2500);
        assertThat(service.merchant.price("item_789")).isEqualTo(12000);

        assertThat(approve(alice, newest, oldToken).statusCode()).isEqualTo(200);
        Map<String, Object> requoted = awaitIntent(alice, newest, i -> quotePrice(i) == 1201);
        assertThat(quotePrice(requoted)).isEqualTo(1201);
        assertThat(requoted).containsEntry("state", "AWAITING_APPROVAL");
        assertThat(token(alice, newest)).as("a new approval").isNotEqualTo(oldToken);
        assertThat(approve(alice, newest, oldToken).statusCode()).as("the old token").isEqualTo(409);
        assertThat(intent(alice, older)).containsEntry("state", "AWAITING_APPROVAL");
        assertThat(quotePrice(intent(alice, older))).isEqualTo(2500);
        assertThat(intent(bob, bobs)).containsEntry("state", "AWAITING_APPROVAL");
        assertThat(quotePrice(intent(bob, bobs))).isEqualTo(12000);
        assertThat(token(bob, bobs)).as("bob's approval is the one he was shown").isEqualTo(bobsToken);
    }

    // Covers: 23.3 (change price with no intent awaiting approval is 422 with a fixed message)
    @Test
    void changePrice_withNothingAwaiting_is422() throws IOException {
        start(Optional.empty());
        Session alice = api.signIn("alice");
        propose("bob", "item_123");

        HttpResponse<String> r = put(alice, "{\"preset\":\"change_price\"}");

        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json(r)).containsEntry("message", "no intent is awaiting approval");
        assertThat(service.merchant.price("item_123")).isEqualTo(2500);
    }

    @SuppressWarnings("unchecked")
    private static long quotePrice(Map<String, Object> intent) {
        Map<String, Object> quote = (Map<String, Object>) intent.get("quote");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) quote.get("lines");
        return ((Number) lines.getFirst().get("unit_price_minor")).longValue();
    }

    // Covers: 23.3 (orders need a known merchant_id, 422 otherwise; reset clears the merchant's orders and the rules)
    @Test
    void ordersAndReset() throws IOException {
        start(Optional.empty());
        Session alice = api.signIn("alice");

        assertThat(api.get(alice, "/api/lab/orders?merchant_id=nope").statusCode()).isEqualTo(422);
        assertThat(api.get(alice, "/api/lab/orders").statusCode()).isEqualTo(422);
        IntentId id = propose("alice", "item_456");
        assertThat(approve(alice, id, token(alice, id)).statusCode()).isEqualTo(200);
        awaitIntent(alice, id, i -> "COMPLETED".equals(i.get("state")));
        assertThat(service.merchant.orders()).hasSize(1);
        assertThat(put(alice, "{\"preset\":\"merchant_down\"}").statusCode()).isEqualTo(200);
        assertThat(viaProxy("/.well-known/ucp")).isEqualTo(-1);

        HttpResponse<String> reset = api.post(alice, "/api/lab/reset", "{}");

        assertThat(reset.statusCode()).isEqualTo(204);
        assertThat(service.merchant.orders()).isEmpty();
        assertThat(viaProxy("/.well-known/ucp")).as("rules cleared").isEqualTo(200);
        assertThat(service.proxy.log(0)).extracting(LogEntry::path).contains("/.well-known/ucp");
    }
}
