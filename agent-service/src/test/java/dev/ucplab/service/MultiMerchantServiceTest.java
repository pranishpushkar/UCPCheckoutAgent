package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Several merchants in the service (m6a C7; DESIGN 19, 23.3, I1, I11, P1): a gateway and a catalog client per configured
 * merchant, the lab per merchant, and the startup refusal for a merchant dropped from the file. Two merchants, A on the
 * built-in catalog and B on {@code dev/catalogs/b.json}, each behind its own fault proxy.
 */
class MultiMerchantServiceTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final String SECRET = HexFormat.of().formatHex(new byte[32]).replace('0', '7');

    @TempDir
    Path dir;

    /** One clock across restarts, so a test can move past the first run's lease, as a restart after a crash would. */
    private final FakeClock clock = new FakeClock(java.time.Instant.parse("2026-11-15T09:00:00Z"));
    private TestService service;
    private ApiClient api;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private void start(String bStatus) throws IOException {
        service = TestService.startMerchants(dir, Map.of("APPROVAL_SECRET", SECRET), Optional.of(clock), bStatus, true);
        api = new ApiClient(service.port());
    }

    private IntentId propose(String user, MerchantId merchant, String item) {
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, merchant,
                List.of(new ProposedLine(item, 1)), "multi-merchant test");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        return result.intentId();
    }

    private HttpResponse<String> approve(Session s, IntentId id) {
        String token = (String) json(api.get(s, "/api/intents/" + id.value() + "/approval")).get("token");
        return api.post(s, "/api/intents/" + id.value() + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}");
    }

    private Map<String, Object> awaitIntent(Session s, IntentId id, Predicate<Map<String, Object>> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> now = json(api.get(s, "/api/intents/" + id.value()));
        while (!done.test(now) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            now = json(api.get(s, "/api/intents/" + id.value()));
        }
        return now;
    }

    private HttpResponse<String> faults(String method, Session s, String path, Optional<String> body) {
        return api.send(method, path, body, "Cookie", "ucplab_session=" + s.cookie(), "X-CSRF-Token", s.csrf(),
                "Content-Type", "application/json");
    }

    private static int via(FaultProxy proxy, String path) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(proxy.baseUrl().resolve(path))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static long checkoutCalls(FaultProxy proxy) {
        return proxy.log(0).stream().map(LogEntry::path).filter(p -> p.startsWith("/ucp/v1/checkout-sessions")).count();
    }

    // Covers: DESIGN 19, I1, I11 (a purchase at merchant B is quoted, completed and reconciled through B's gateway and B's
    // proxy: one order at B, none at A, and A's proxy never sees a checkout call)
    @Test
    void aPurchaseAtB_completesThroughBsProxy_andANeverSeesIt() throws IOException {
        start("active");
        Session alice = api.signIn("alice");
        IntentId id = propose("alice", B, "b_mug_01");

        assertThat(approve(alice, id).statusCode()).isEqualTo(200);
        Map<String, Object> done = awaitIntent(alice, id, i -> "COMPLETED".equals(i.get("state")));

        assertThat(done).containsEntry("state", "COMPLETED").containsEntry("merchant_id", "merchant-b");
        assertThat(service.merchantB.orders()).hasSize(1);
        assertThat(service.merchant.orders()).isEmpty();
        assertThat(checkoutCalls(service.proxyB)).as("B's proxy saw the checkout").isGreaterThanOrEqualTo(2);
        assertThat(checkoutCalls(service.proxy)).as("A's proxy saw no checkout call").isZero();
    }

    // Covers: DESIGN 19, P1 (both merchants are ACTIVE after the startup check, and the registry lists them in file order)
    @Test
    void theRegistry_listsBothMerchantsActive_inFileOrder() throws IOException {
        start("active");

        assertThat(service.bean(MerchantRegistry.class).active()).extracting(e -> e.id()).containsExactly(A, B);
        assertThat(service.bean(MerchantRegistry.class).find(B).map(e -> e.status())).contains(MerchantStatus.ACTIVE);
    }

    // Covers: DESIGN 19, 23.3 (faults at B do not touch A: with merchant_down at B, A's proxy still answers and B's does not;
    // DELETE with merchant_id clears only B)
    @Test
    void faultsAtB_doNotAffectA() throws IOException {
        start("active");
        Session alice = api.signIn("alice");

        HttpResponse<String> put = faults("PUT", alice, "/api/lab/faults",
                Optional.of("{\"preset\":\"merchant_down\",\"merchant_id\":\"merchant-b\"}"));

        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(via(service.proxy, "/.well-known/ucp")).isEqualTo(200);
        assertThat(via(service.proxyB, "/.well-known/ucp")).isNotEqualTo(200);
        assertThat(faults("DELETE", alice, "/api/lab/faults?merchant_id=merchant-b", Optional.empty()).statusCode())
                .isEqualTo(204);
        assertThat(via(service.proxyB, "/.well-known/ucp")).isEqualTo(200);
    }

    // Covers: 23.3 (M6's API is unchanged: PUT without merchant_id still acts on merchant A, the first of the file, and DELETE
    // without it clears A)
    @Test
    void putFaultsWithoutAMerchantId_stillHitsMerchantA() throws IOException {
        start("active");
        Session alice = api.signIn("alice");

        assertThat(faults("PUT", alice, "/api/lab/faults", Optional.of("{\"preset\":\"merchant_down\"}")).statusCode())
                .isEqualTo(200);

        assertThat(via(service.proxy, "/.well-known/ucp")).isNotEqualTo(200);
        assertThat(via(service.proxyB, "/.well-known/ucp")).isEqualTo(200);
        assertThat(faults("DELETE", alice, "/api/lab/faults", Optional.empty()).statusCode()).isEqualTo(204);
        assertThat(via(service.proxy, "/.well-known/ucp")).isEqualTo(200);
    }

    // Covers: 23.3 (an unknown merchant_id is refused with 422 on faults and on orders, and nothing is installed)
    @Test
    void anUnknownMerchantId_isRefused() throws IOException {
        start("active");
        Session alice = api.signIn("alice");

        assertThat(faults("PUT", alice, "/api/lab/faults",
                Optional.of("{\"preset\":\"merchant_down\",\"merchant_id\":\"merchant-z\"}")).statusCode()).isEqualTo(422);
        assertThat(faults("DELETE", alice, "/api/lab/faults?merchant_id=merchant-z", Optional.empty()).statusCode())
                .isEqualTo(422);
        assertThat(api.get(alice, "/api/lab/orders?merchant_id=merchant-z").statusCode()).isEqualTo(422);
        assertThat(via(service.proxy, "/.well-known/ucp")).isEqualTo(200);
        assertThat(via(service.proxyB, "/.well-known/ucp")).isEqualTo(200);
    }

    // Covers: 23.3 (reset covers every merchant: both proxies are cleared and both merchants return to their catalog)
    @Test
    void reset_coversEveryMerchant() throws IOException {
        start("active");
        Session alice = api.signIn("alice");
        faults("PUT", alice, "/api/lab/faults", Optional.of("{\"preset\":\"merchant_down\"}"));
        faults("PUT", alice, "/api/lab/faults", Optional.of("{\"preset\":\"merchant_down\",\"merchant_id\":\"merchant-b\"}"));
        service.merchantB.setPrice("b_mug_01", 5);

        assertThat(api.post(alice, "/api/lab/reset", "{}").statusCode()).isEqualTo(204);

        assertThat(via(service.proxy, "/.well-known/ucp")).isEqualTo(200);
        assertThat(via(service.proxyB, "/.well-known/ucp")).isEqualTo(200);
        assertThat(service.merchantB.price("b_mug_01")).isEqualTo(1100L);
    }

    // Covers: 23.3, I1 (orders are read per merchant, straight from that merchant)
    @Test
    void orders_areReadPerMerchant() throws IOException {
        start("active");
        Session alice = api.signIn("alice");
        IntentId id = propose("alice", B, "b_tee_02");
        approve(alice, id);
        awaitIntent(alice, id, i -> "COMPLETED".equals(i.get("state")));

        assertThat((List<?>) json(api.get(alice, "/api/lab/orders?merchant_id=merchant-b")).get("orders")).hasSize(1);
        assertThat((List<?>) json(api.get(alice, "/api/lab/orders?merchant_id=merchant-a")).get("orders")).isEmpty();
    }

    // Covers: DESIGN 19, P1 (a merchant the file marks suspended is left out of active() and a proposal at it is denied)
    @Test
    void aSuspendedMerchant_isDenied_forANewIntent() throws IOException {
        start("suspended");

        assertThat(service.bean(MerchantRegistry.class).active()).extracting(e -> e.id()).containsExactly(A);
        AuthContext auth = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(), "s");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, B,
                List.of(new ProposedLine("b_mug_01", 1)), "suspended");

        assertThat(result.state()).isEqualTo(IntentState.REJECTED);
        assertThat(service.merchantB.orders()).isEmpty();
    }

    // Covers: DESIGN 19, 20, I1 (an in-flight intent at a merchant that is SUSPENDED when the service restarts still has its
    // gateway: approved, it completes through that merchant's own gateway and proxy)
    @Test
    void anInFlightIntentAtASuspendedMerchant_stillCompletes() throws IOException {
        start("active");
        IntentId id = propose("alice", B, "b_mug_01");
        clock.advance(Duration.ofMinutes(2));

        service = TestService.restartMerchants(service, dir, Map.of("APPROVAL_SECRET", SECRET), Optional.of(clock),
                "suspended", true);
        api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        assertThat(service.bean(MerchantRegistry.class).find(B).map(e -> e.status())).contains(MerchantStatus.SUSPENDED);
        HttpResponse<String> approved = approve(alice, id);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        Map<String, Object> done = awaitIntent(alice, id, i -> "COMPLETED".equals(i.get("state")));

        assertThat(done).containsEntry("state", "COMPLETED");
        assertThat(service.merchantB.orders()).hasSize(1);
        assertThat(service.merchant.orders()).isEmpty();
    }

    // Covers: DESIGN 19, 18.1 (a merchant dropped from the file while an intent at it awaits approval refuses startup with the
    // fixed message, which names neither the merchant nor the file)
    @Test
    void removingAMerchantWithAnAwaitingIntent_refusesStartup() throws IOException {
        start("active");
        propose("alice", B, "b_mug_01");
        TestService first = service;
        service = null;

        try {
            assertThatThrownBy(() -> TestService.restartMerchants(first, dir, Map.of("APPROVAL_SECRET", SECRET),
                    Optional.of(clock), "active", false).close()).hasStackTraceContaining(
                    "an intent in flight names a merchant that MERCHANTS_FILE does not list");
        } finally {
            first.close();
        }
    }

    // Covers: DESIGN 19 (a merchant with no intent in flight can be dropped from the file: only in-flight intents block it)
    @Test
    void removingAMerchantWithNoIntentInFlight_isAllowed() throws IOException {
        start("active");

        service = TestService.restartMerchants(service, dir, Map.of("APPROVAL_SECRET", SECRET), Optional.of(clock),
                "active", false);

        assertThat(service.bean(MerchantRegistry.class).active()).extracting(e -> e.id()).containsExactly(A);
    }
}
