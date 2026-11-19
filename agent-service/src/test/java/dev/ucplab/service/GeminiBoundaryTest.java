package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.core.intent.HandoffWatch;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StoreOrder;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.config.MerchantTransports;
import dev.ucplab.ucp.mcp.McpStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 26.6: what reaches Gemini in a console run at the owner's store. The whole service runs with {@code AGENT_MODEL=gemini}
 * and the real Gemini client against a loopback stub of the API, a {@code completion: handoff} MCP store in place of
 * the network, and a canary in every value that must stay out of the model's context: the client id and secret, the
 * access token, the buyer IP, the handoff URL, the store order's id and confirmation code, the approval token, the
 * session cookie and the CSRF token. Every request the stub receives is scanned.
 */
class GeminiBoundaryTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FAKE_KEY = "fake-key-not-a-secret-2468013579";
    private static final String HOST = "example-dev.myshopify.com";
    private static final String BOARD = "gid://shopify/ProductVariant/51192065753174";

    @TempDir
    Path dir;

    private final String clientId = "CID" + hex(6);
    private final String clientSecret = "CSEC" + hex(8);
    private final String accessToken = "shpat_CANARY" + hex(8);
    private final String buyerIp = "81.2.69." + (20 + RANDOM.nextInt(200));
    private final String pathCanary = "HANDOFF-PATH-" + hex(8);
    private final String keyCanary = "HANDOFF-KEY-" + hex(8);
    private final String url = "https://" + HOST + "/cart/c/" + pathCanary + "?key=" + keyCanary;
    private final String orderId = "gid://shopify/Order/" + (100000 + RANDOM.nextInt(800000));
    private final String label = "CONF" + hex(4).toUpperCase(java.util.Locale.ROOT);
    private final McpStore store = new McpStore();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<String>> turns = new ArrayDeque<>();
    private HttpServer stub;
    private TestService service;

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        if (stub != null) {
            stub.stop(0);
        }
    }

    private static String call(String name, String args) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + name
                + "\",\"args\":" + args + "}}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":5}}";
    }

    private static String text(String said) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + said + "\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":60,\"candidatesTokenCount\":4}}";
    }

    /** The store's host goes to the store; the only other host called, the token endpoint, gets the canary token. */
    private HttpTransport network() {
        return call -> {
            if (!call.uri().getHost().equals(HOST)) {
                return new HttpReply(200, Map.of(), ("{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return store.send(call);
        };
    }

    /** The Gemini stub: every request kept whole (URI, headers but the key's, body); each answer the next turn. */
    private void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            Map<String, List<String>> headers = new HashMap<>(ex.getRequestHeaders());
            headers.keySet().removeIf(h -> h.equalsIgnoreCase("x-goog-api-key"));
            requests.add(ex.getRequestURI() + "\n" + headers + "\n"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Supplier<String> next = turns.poll();
            byte[] out = (next == null ? text("Done.") : next.get()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
    }

    private void start() throws IOException {
        startStub();
        Path merchants = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: dev-store
                    name: Dev store
                    url: https://%s
                    transport: mcp
                    completion: handoff
                    owned: true
                    shop_domain: %s
                    credential_ref: env:SHOPIFY_CLIENT
                    requires_buyer_ip: true
                    ucp_versions: ["2026-04-08"]
                """.formatted(HOST, HOST));
        Path policy = Files.writeString(dir.resolve("policy.yaml"), """
                per_purchase_cap_minor: 100000
                daily_cap_minor: 200000
                """);
        Map<String, String> env = new HashMap<>();
        env.put("MERCHANTS_FILE", merchants.toString());
        env.put("POLICY_FILE", policy.toString());
        env.put("SHOPIFY_CLIENT", clientId + ":" + clientSecret);
        env.put("BUYER_IP", buyerIp);
        env.put("UCP_AGENT_PROFILE", "https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json");
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        env.put("AGENT_MODEL", "gemini");
        env.put("GEMINI_API_KEY", FAKE_KEY);
        env.put("GEMINI_BASE_URL", "http://127.0.0.1:" + stub.getAddress().getPort());
        service = TestService.start(dir, env, Optional.empty(), List.of(), Optional.empty(),
                Map.of("testMerchantTransports", (MerchantTransports) this::network));
    }

    private static String budgeted(String message, long minor, String currency) {
        return "{\"message\":\"" + message + "\",\"budget_minor\":" + minor + ",\"currency\":\"" + currency + "\"}";
    }

    private static String proposal(String extra) {
        return "{\"merchant_id\":\"dev-store\",\"items\":[{\"item_id\":\"" + BOARD + "\",\"quantity\":1}],"
                + "\"rationale\":\"a beginner board\"" + extra + "}";
    }

    private Map<String, Object> awaitIntent(ApiClient api, Session s, String id, String state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> now = json(api.get(s, "/api/intents/" + id));
        while (!state.equals(now.get("state")) && System.nanoTime() < deadline) {
            pause(25);
            now = json(api.get(s, "/api/intents/" + id));
        }
        return now;
    }

    private Map<String, Object> awaitRun(ApiClient api, Session s, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> run = json(api.get(s, "/api/runs/" + id));
        while (!"done".equals(run.get("status")) && System.nanoTime() < deadline) {
            pause(25);
            run = json(api.get(s, "/api/runs/" + id));
        }
        return run;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The store's order, seen by the follow-up, as the orchestrator's write would leave it (7.6, 26.1). */
    private void orderSeen(IntentId id) {
        SqlIntentRepository intents = service.bean(SqlIntentRepository.class);
        String owner = intents.findForSystem(id).orElseThrow().leaseOwner().orElse("test-writer");
        PurchaseIntent claimed = intents.claim(id, owner, Duration.ofSeconds(30)).orElseThrow();
        intents.update(claimed.withHandoffWatch(new HandoffWatch(Optional.empty(), Optional.empty(),
                Optional.of(new StoreOrder(orderId, Optional.of(label))))), claimed.version(), owner);
        intents.releaseLease(id, owner);
    }

    @SuppressWarnings("unchecked")
    private static List<String> intentIds(Map<String, Object> run) {
        return (List<String>) run.get("intent_ids");
    }

    // Covers: 26.6, 6.4, 6.2, I6, I8, S10 (with the live client at a handoff store: the model gets the system prompt
    // with the [BUDGET] hint, the conversation and the tool results, the store's product text among them; it never gets
    // the client id or secret, the access token, the buyer IP, the handoff URL or its key, the store order's id or
    // confirmation code, the approval token, the session cookie or the CSRF token)
    @Test
    void theLiveClient_getsTheConversationAndToolResults_andNothingForbidden() throws IOException {
        store.answer("search_catalog", "catalog_search_store.json");
        store.answer("create_checkout", "create_checkout.json");
        for (int i = 0; i < 4; i++) {
            store.answer("get_checkout", "create_checkout.json",
                    body -> body.replace("https://example-dev.myshopify.com/...", url));
        }
        start();
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        turns.add(() -> call("search_catalog", "{\"query\":\"snowboard\",\"max_results\":5}"));
        turns.add(() -> call("propose_purchase", proposal("")));
        turns.add(() -> text("I asked for your approval."));

        HttpResponse<String> first = api.post(alice, "/api/runs",
                budgeted("a snowboard under $750 for a beginner", 75_000, "USD"));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        Map<String, Object> run = json(first);
        assertThat(run).containsEntry("status", "waiting_for_approval");
        String id = intentIds(run).getFirst();
        assertThat(awaitIntent(api, alice, id, "AWAITING_APPROVAL")).containsEntry("state", "AWAITING_APPROVAL");

        Map<String, Object> approval = json(api.get(alice, "/api/intents/" + id + "/approval"));
        String token = (String) approval.get("token");
        assertThat(api.post(alice, "/api/intents/" + id + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}").statusCode()).isEqualTo(200);
        assertThat(awaitIntent(api, alice, id, "HANDED_OFF")).containsEntry("handoff_url", url);
        assertThat(awaitRun(api, alice, (String) run.get("run_id"))).containsEntry("status", "done");
        orderSeen(new IntentId(id));
        assertThat(json(api.get(alice, "/api/intents/" + id)).get("store_order").toString()).contains(label);

        turns.add(() -> call("get_purchase_status", "{\"intent_id\":\"" + id + "\"}"));
        turns.add(() -> text("Your purchase is with the store."));
        Map<String, Object> second = json(api.post(alice, "/api/runs", "{\"message\":\"where is my board?\"}"));
        assertThat(second).containsEntry("status", "done");

        String sent = String.join("\n----\n", requests);
        assertThat(requests).hasSize(5);
        // What the model is meant to get (26.6): the request, the store's product text and the budget hint.
        assertThat(sent).contains("a snowboard under $750 for a beginner").contains("The Complete Snowboard")
                .contains("[BUDGET] The user's budget for this run is $750.00").contains("<untrusted")
                .contains("AWAITING_APPROVAL").contains("handed to the store for payment");
        List<String> found = new ArrayList<>();
        Map<String, String> forbidden = new java.util.LinkedHashMap<>();
        forbidden.put("the client id", clientId);
        forbidden.put("the client secret", clientSecret);
        forbidden.put("the access token", accessToken);
        forbidden.put("the buyer IP", buyerIp);
        forbidden.put("the handoff URL's path", pathCanary);
        forbidden.put("the handoff URL's key", keyCanary);
        forbidden.put("the store order's id", orderId);
        forbidden.put("the confirmation code", label);
        forbidden.put("the approval token", token);
        forbidden.put("the session cookie", alice.cookie());
        forbidden.put("the CSRF token", alice.csrf());
        forbidden.put("the checkout's key", "?key=");
        forbidden.forEach((name, value) -> {
            if (sent.contains(value)) {
                found.add(name);
            }
        });
        assertThat(found).as("values that reached the model").isEmpty();
    }

    // Covers: 26.6, 6.2, P9, S29, 23.1 (under the live client the model cannot set, raise or clear the budget: a
    // propose_purchase carrying a budget argument is refused as unknown and creates nothing; the proposal without it
    // is refused by P9 against the run's own budget, which the intent keeps; the model is shown the P9 result)
    @Test
    void theLiveClient_cannotSetTheBudget_andP9HoldsTheRunsOwn() throws IOException {
        store.answer("create_checkout", "create_checkout.json");
        store.answer("cancel_checkout", "cancel_checkout.json");
        start();
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        turns.add(() -> call("propose_purchase", proposal(",\"budget_minor\":9999999")));
        turns.add(() -> call("propose_purchase", proposal(",\"budget\":{\"amount_minor\":9999999,\"currency\":\"USD\"}")));
        turns.add(() -> call("propose_purchase", proposal("")));
        turns.add(() -> text("None fits."));

        Map<String, Object> run = json(api.post(alice, "/api/runs", budgeted("a snowboard", 50_000, "USD")));

        assertThat(run).containsEntry("status", "done");
        assertThat(intentIds(run)).hasSize(1);
        String id = intentIds(run).getFirst();
        Map<String, Object> intent = awaitIntent(api, alice, id, "FAILED_NOT_CHARGED");
        assertThat(intent).containsEntry("state", "FAILED_NOT_CHARGED").containsEntry("failure_reason", "policy_P9")
                .containsEntry("budget", Map.of("currency", "USD", "amount_minor", 50_000L));
        assertThat(store.toolCalls()).extracting(McpStore.Sent::tool).containsOnlyOnce("create_checkout");
        assertThat(requests).hasSize(4);
        assertThat(requests.get(1)).contains("unknown");
        assertThat(requests.get(2)).contains("unknown");
        assertThat(requests.get(3)).contains("over your budget of $500.00 (P9)");
    }
}
