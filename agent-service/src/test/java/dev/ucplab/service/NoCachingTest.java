package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.config.ServiceProperties;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nothing is cached (section 20 [CONTRACT], m6-plan section 11, C17): every {@code /api} and {@code /auth} response, an
 * error included, is {@code no-store} with {@code Pragma: no-cache} and no validator; a conditional request never gets a
 * 304; and a read after a change shows the change.
 */
class NoCachingTest {

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private AuthContext alice() {
        Clock clock = service.bean(Clock.class);
        return new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(), "test-session");
    }

    private IntentId propose(String item) {
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(alice(), ServiceProperties.MERCHANT,
                List.of(new ProposedLine(item, 1)), "caching test");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        return result.intentId();
    }

    private static void assertUncacheable(HttpResponse<String> r, String what) {
        assertThat(r.headers().allValues("Cache-Control")).as("Cache-Control of " + what).containsExactly("no-store");
        assertThat(r.headers().allValues("Pragma")).as("Pragma of " + what).containsExactly("no-cache");
        assertThat(r.headers().firstValue("ETag")).as("ETag of " + what).isEmpty();
        assertThat(r.headers().firstValue("Last-Modified")).as("Last-Modified of " + what).isEmpty();
        assertThat(r.headers().firstValue("Expires")).as("Expires of " + what).isEmpty();
    }

    // Covers: 20, 23.1, 23.2, 23.3, 23.5, 18.5 (every route of 23.1 to 23.3, /api/me and the /auth routes, and the 401,
    // 403 and 404 answers of each family: no-store, no-cache, no ETag, no Last-Modified, no Expires)
    @Test
    void everyApiAndAuthResponse_isNoStore_withNoValidator() throws IOException {
        service = TestService.start(dir);
        api = new ApiClient(service.port());
        Session s = api.signIn("alice");
        IntentId intent = propose("item_456");
        String run = (String) json(api.post(s, "/api/runs", "{\"message\":\"hi\"}")).get("run_id");
        String path = "/api/intents/" + intent.value();
        List<String[]> calls = new ArrayList<>();
        // method, path, body (or null)
        calls.add(new String[] {"GET", "/api/me", null});
        calls.add(new String[] {"GET", "/api/runs/" + run, null});
        calls.add(new String[] {"POST", "/api/runs/" + run + "/messages", "{\"message\":\"a mug\"}"});
        calls.add(new String[] {"POST", "/api/runs", "{\"message\":\"a second run\"}"});
        calls.add(new String[] {"GET", "/api/intents", null});
        calls.add(new String[] {"GET", path, null});
        calls.add(new String[] {"GET", path + "/events", null});
        calls.add(new String[] {"GET", path + "/approval", null});
        calls.add(new String[] {"POST", path + "/approval", "{\"token\":\"not-the-token\",\"decision\":\"approve\"}"});
        calls.add(new String[] {"PUT", "/api/lab/faults", "{\"preset\":\"drop_next_complete\"}"});
        calls.add(new String[] {"DELETE", "/api/lab/faults", null});
        calls.add(new String[] {"GET", "/api/lab/orders?merchant_id=merchant-a", null});
        calls.add(new String[] {"POST", "/api/lab/reset", "{}"});
        // The errors of each kind.
        calls.add(new String[] {"GET", "/api/runs/no-such-run", null});
        calls.add(new String[] {"GET", "/api/intents/no-such-intent", null});
        calls.add(new String[] {"GET", "/api/nope", null});
        calls.add(new String[] {"POST", "/api/runs", "not json"});

        for (String[] c : calls) {
            HttpResponse<String> r = api.send(c[0], c[1], Optional.ofNullable(c[2]), "Cookie",
                    "ucplab_session=" + s.cookie(), "X-CSRF-Token", s.csrf(), "Content-Type", "application/json");
            assertUncacheable(r, c[0] + " " + c[1] + " (" + r.statusCode() + ")");
        }
        assertUncacheable(api.get("/api/me"), "GET /api/me without a session (401)");
        assertUncacheable(api.send("POST", "/api/runs", Optional.of("{\"message\":\"x\"}"), "Cookie",
                "ucplab_session=" + s.cookie(), "Content-Type", "application/json"), "POST without a CSRF token (403)");
        assertUncacheable(api.devLogin("bob", Optional.empty(), Optional.empty()), "POST /auth/dev-login");
        assertUncacheable(api.post(s, "/auth/logout", "{}"), "POST /auth/logout");
    }

    // Covers: 20 (a conditional request is answered in full: If-None-Match: * and an old If-Modified-Since never give a
    // 304, for each GET route, and the body is there)
    @Test
    void conditionalRequests_areNeverAnswered304() throws IOException {
        service = TestService.start(dir);
        api = new ApiClient(service.port());
        Session s = api.signIn("alice");
        IntentId intent = propose("item_456");
        String run = (String) json(api.post(s, "/api/runs", "{\"message\":\"hi\"}")).get("run_id");
        String path = "/api/intents/" + intent.value();

        for (String get : List.of("/api/me", "/api/runs/" + run, "/api/intents", path, path + "/events",
                path + "/approval", "/api/lab/orders?merchant_id=merchant-a")) {
            for (String[] validator : new String[][] {{"If-None-Match", "*"},
                    {"If-Modified-Since", "Fri, 01 Jan 2100 00:00:00 GMT"}}) {
                HttpResponse<String> first = api.get(get, "Cookie", "ucplab_session=" + s.cookie());
                HttpResponse<String> again = api.get(get, "Cookie", "ucplab_session=" + s.cookie(), validator[0],
                        validator[1]);
                assertThat(again.statusCode()).as("%s with %s", get, validator[0]).isEqualTo(200)
                        .isEqualTo(first.statusCode());
                assertThat(again.body()).as("the body of %s", get).isNotBlank();
            }
        }
    }

    // Covers: 20 (read after change: an intent changed behind the API, in the service's own beans, reads as changed on the
    // very next GET, and a re-quote's new approval is what the approval GET then returns)
    @Test
    void readAfterChange_showsTheChange() throws IOException {
        service = TestService.start(dir);
        api = new ApiClient(service.port());
        Session s = api.signIn("alice");
        IntentId intent = propose("item_456");
        String path = "/api/intents/" + intent.value();
        assertThat(json(api.get(s, path))).containsEntry("state", "AWAITING_APPROVAL");

        // A re-quote: the price changes at the merchant, an approve notices, and the approval is a new one.
        Map<String, Object> before = json(api.get(s, path + "/approval"));
        long totalBefore = ((Number) ((Map<?, ?>) before.get("snapshot")).get("total_minor")).longValue();
        assertThat(api.send("PUT", "/api/lab/faults", Optional.of("{\"preset\":\"change_price\"}"), "Cookie",
                "ucplab_session=" + s.cookie(), "X-CSRF-Token", s.csrf(), "Content-Type", "application/json")
                .statusCode()).isEqualTo(200);
        api.post(s, path + "/approval", "{\"token\":\"" + before.get("token") + "\",\"decision\":\"approve\"}");
        long deadline = System.nanoTime() + 30_000_000_000L;
        long totalAfter = totalBefore;
        while (totalAfter == totalBefore && System.nanoTime() < deadline) {
            Map<String, Object> now = json(api.get(s, path + "/approval"));
            if (now.get("snapshot") instanceof Map<?, ?> snap) {
                totalAfter = ((Number) snap.get("total_minor")).longValue();
            }
            Thread.onSpinWait();
        }
        assertThat(totalAfter).as("the approval GET after the re-quote").isGreaterThan(totalBefore);

        // A change made directly through the service's own approval service, not the API: a rejection.
        PendingApproval pending = service.bean(DefaultApprovalService.class).pending(alice(), intent).orElseThrow();
        service.bean(DefaultApprovalService.class).decide(alice(), intent, pending.token(), false);
        assertThat(json(api.get(s, path))).containsEntry("state", "REJECTED");
    }
}
