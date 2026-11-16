package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.LeakEvidence.Allowed;
import dev.ucplab.service.config.MerchantTransports;
import dev.ucplab.ucp.HttpReply;
import dev.ucplab.ucp.HttpTransport;
import dev.ucplab.ucp.mcp.McpStore;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H5 C7c: the whole service started with a {@code completion: handoff} MCP merchant (19, 7.2), the scripted store in
 * place of the network, so nothing leaves the JVM. A proposal, the approval answer with the handoff note, an approval
 * over the API, the hand-off, the owner's intent with its handoff_url, and a run whose model asks for the status; then
 * the I8 scan: the URL is in the owner's intent answer and its own column only, in no log, event, trace or other answer.
 */
class HandoffServiceTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MerchantId STORE = new MerchantId("dev-store");
    private static final String HOST = "example-dev.myshopify.com";
    private static final List<ProposedLine> ONE_BOARD =
            List.of(new ProposedLine("gid://shopify/ProductVariant/51192065753174", 1));
    static final String APPROVAL_NOTE =
            "Shipping and tax are added by the store; you see the final total before you pay there.";
    private static final Map<String, String> LEVELS = Map.of("logging.level.root", "DEBUG",
            "logging.level.org.springframework.web", "TRACE", "logging.level.org.apache.tomcat", "TRACE");

    @TempDir
    Path dir;

    private final String pathCanary = "HANDOFF-PATH-" + hex(8);
    private final String keyCanary = "HANDOFF-KEY-" + hex(8);
    private final String url = "https://" + HOST + "/cart/c/" + pathCanary + "?key=" + keyCanary;
    private final McpStore store = new McpStore();
    private final AtomicReference<String> asked = new AtomicReference<>("");
    private final AtomicReference<String> seen = new AtomicReference<>("");
    private LogCapture logs;
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
        LEVELS.keySet().forEach(System::clearProperty);
        if (logs != null) {
            logs.close();
        }
    }

    /** The store's host goes to the store; the only other host called, the token endpoint, gets a token. */
    private HttpTransport network() {
        return call -> {
            if (!call.uri().getHost().equals(HOST)) {
                return new HttpReply(200, Map.of(), "{\"access_token\":\"tok-1\",\"expires_in\":3600}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return store.send(call);
        };
    }

    /** Asks for the status of the intent in {@link #asked}, keeps the result it is shown in {@link #seen}, answers. */
    private ModelClient statusModel() {
        return (conversation, tools) -> {
            Optional<Message.ToolResult> result = conversation.messages().stream()
                    .filter(m -> m instanceof Message.ToolResult).map(m -> (Message.ToolResult) m).findFirst();
            result.ifPresent(r -> seen.set(r.json()));
            if (result.isEmpty()) {
                return new ModelTurn("", List.of(new ToolCall("s1", "get_purchase_status",
                        Map.of("intent_id", asked.get()))), Usage.NONE);
            }
            return new ModelTurn("Your purchase is with the store.", List.of(), Usage.NONE);
        };
    }

    private void start() throws IOException {
        logs = new LogCapture();
        LEVELS.forEach(System::setProperty);
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
                    ucp_versions: ["2026-04-08"]
                """.formatted(HOST, HOST));
        Path policy = Files.writeString(dir.resolve("policy.yaml"), """
                per_purchase_cap_minor: 100000
                daily_cap_minor: 200000
                """);
        Map<String, String> env = new HashMap<>();
        env.put("MERCHANTS_FILE", merchants.toString());
        env.put("POLICY_FILE", policy.toString());
        env.put("SHOPIFY_CLIENT", "client-1:secret-1");
        env.put("UCP_AGENT_PROFILE", "https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json");
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        service = TestService.start(dir, env, Optional.empty(), List.of(), Optional.empty(), Map.of(
                "testMerchantTransports", (MerchantTransports) this::network, "testModel", statusModel()));
    }

    private IntentId propose(String user) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, STORE, ONE_BOARD, "a board");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        return result.intentId();
    }

    private Map<String, Object> awaitIntent(ApiClient api, Session s, IntentId id, String state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> now = json(api.get(s, "/api/intents/" + id.value()));
        while (!state.equals(now.get("state")) && System.nanoTime() < deadline) {
            pause(25);
            now = json(api.get(s, "/api/intents/" + id.value()));
        }
        return now;
    }

    private Map<String, Object> awaitRun(ApiClient api, Session s, String id) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> run = json(api.get(s, "/api/runs/" + id));
        while ("running".equals(run.get("status")) && System.nanoTime() < deadline) {
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

    // Covers: 7.2 hand_off, 19, 23.2, 6.2, I6, I8, I9, H5 C7c (a completion: handoff MCP merchant builds a stack and
    // starts; its approval answer carries completion and the fixed note outside the snapshot; an approval over the API
    // hands the intent off; the owner's intent answer carries handoff_url, another user's is 404 like a missing one; the
    // handed_off event holds the host only; the model's get_purchase_status sees the fixed note, never the URL; and the
    // URL is in no log line, trace line, event, other answer or database column but handoff_url)
    @Test
    void aHandoffMerchant_handsOffOverTheApi_andTheUrlReachesOnlyTheOwner() throws IOException {
        store.answer("create_checkout", "create_checkout.json");
        for (int i = 0; i < 3; i++) {
            store.answer("get_checkout", "create_checkout.json",
                    body -> body.replace("https://example-dev.myshopify.com/...", url));
        }
        start();
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        IntentId id = propose("alice");

        HttpResponse<String> pending = api.get(alice, "/api/intents/" + id.value() + "/approval");
        assertThat(pending.statusCode()).isEqualTo(200);
        Map<String, Object> approval = json(pending);
        assertThat(approval).containsEntry("completion", "handoff").containsEntry("note", APPROVAL_NOTE);
        assertThat(approval.get("snapshot").toString()).doesNotContain("completion").doesNotContain("note=");
        String token = (String) approval.get("token");
        HttpResponse<String> decided = api.post(alice, "/api/intents/" + id.value() + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}");
        assertThat(decided.statusCode()).isEqualTo(200);

        Map<String, Object> intent = awaitIntent(api, alice, id, "HANDED_OFF");
        assertThat(intent).containsEntry("state", "HANDED_OFF").containsEntry("handoff_url", url);
        assertThat(api.get(alice, "/api/intents").statusCode()).isEqualTo(200);
        assertThat(api.get(alice, "/api/intents/" + id.value() + "/events").statusCode()).isEqualTo(200);
        HttpResponse<String> other = api.get(bob, "/api/intents/" + id.value());
        HttpResponse<String> missing = api.get(bob, "/api/intents/no-such-intent");
        assertThat(other.statusCode()).isEqualTo(404);
        Map<String, Object> theirs = new HashMap<>(json(other));
        Map<String, Object> none = new HashMap<>(json(missing));
        theirs.remove("request_id");
        none.remove("request_id");
        assertThat(theirs).isEqualTo(none);
        assertThat(other.body()).doesNotContain(pathCanary).doesNotContain(keyCanary);
        assertThat(store.toolCalls()).extracting(McpStore.Sent::tool).doesNotContain("complete_checkout");

        List<IntentEvent> events = service.bean(SqlIntentRepository.class).events(id);
        assertThat(events).filteredOn(e -> e.type().equals("handed_off")).singleElement()
                .satisfies(e -> assertThat(LenientJson.readObject(e.payloadJson())).isEqualTo(Map.of("host", HOST)));

        asked.set(id.value());
        Map<String, Object> run = json(api.post(alice, "/api/runs", "{\"message\":\"where is my board?\"}"));
        Map<String, Object> ended = awaitRun(api, alice, (String) run.get("run_id"));
        assertThat(ended).containsEntry("status", "done");
        assertThat(LenientJson.readObject(seen.get())).as("what the model saw (I6)")
                .isEqualTo(Map.of("state", "HANDED_OFF", "note", "handed to the store for payment"));
        assertThat(logs.text()).contains("dev.ucplab.trace").contains("get_purchase_status");

        LeakEvidence evidence = new LeakEvidence();
        evidence.canary("the handoff URL", url, Allowed.HANDOFF_BODY, "purchase_intent.handoff_url");
        evidence.canary("the handoff URL's path", pathCanary, Allowed.HANDOFF_BODY, "purchase_intent.handoff_url");
        evidence.canary("the handoff URL's key", keyCanary, Allowed.HANDOFF_BODY, "purchase_intent.handoff_url");
        evidence.clients(api);
        evidence.database(dir.resolve("service.db"));
        service.close();
        service = null;
        evidence.logs(logs.lines());
        assertThat(evidence.findings()).as("the handoff URL where it must not be").isEmpty();
    }
}
