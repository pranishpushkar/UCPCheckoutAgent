package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.BuyerCountry;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real catalog stage 2: the whole service started with a {@code transport: catalog, completion: link} entry (19,
 * 26.7), the catalog a loopback stub answering with the owner-approved fixture, so nothing leaves the JVM. A proposal,
 * the approval answer with the link note and seller, an approval over the API, the hand-off, and the owner's intent
 * with its handoff_url; then the I8 scan: the link is in the owner's intent answer, its own column and the approval's
 * snapshot only, in no log, event or other answer.
 */
class LinkServiceTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MerchantId CATALOG = new MerchantId("shopify-catalog");
    private static final String ITEM = "gid://shopify/ProductVariant/1000001";
    static final String LINK_NOTE =
            "Listed price from Shopify's catalog. The store adds any shipping and tax at its own checkout.";
    private static final Map<String, String> LEVELS = Map.of("logging.level.root", "DEBUG",
            "logging.level.org.springframework.web", "TRACE", "logging.level.org.apache.tomcat", "TRACE");

    @TempDir
    Path dir;

    private final String gsidCanary = "LINK-GSID-" + hex(8);
    private final String clickCanary = "LINK-CLICK-" + hex(8);
    private final McpStore store = new McpStore();
    private LogCapture logs;
    private TestService service;

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The fixture's checkout_url with the canaries in place of its fake tracking values. */
    private String link() {
        return fixtureLink().replace("FAKEGSID0001", gsidCanary).replace("FAKECLICK0001", clickCanary);
    }

    @SuppressWarnings("unchecked")
    private static String fixtureLink() {
        Map<String, Object> answer = LenientJson.readObject(McpStore.catalogFixture("global_get_product_us.json"));
        Map<String, Object> product = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                answer.get("result")).get("structuredContent")).get("product");
        return (String) ((List<Map<String, Object>>) product.get("variants")).getFirst().get("checkout_url");
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

    private void start() throws IOException {
        logs = new LogCapture();
        LEVELS.forEach(System::setProperty);
        Path merchants = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: shopify-catalog
                    name: Shopify catalog
                    url: http://127.0.0.1:8191/api/ucp/mcp
                    transport: catalog
                    completion: link
                """);
        Path policy = Files.writeString(dir.resolve("policy.yaml"), """
                per_purchase_cap_minor: 100000
                daily_cap_minor: 200000
                """);
        Map<String, String> env = new HashMap<>();
        env.put("MERCHANTS_FILE", merchants.toString());
        env.put("POLICY_FILE", policy.toString());
        env.put("UCP_AGENT_PROFILE", "https://shopify.dev/ucp/agent-profiles/2026-04-08/valid-with-capabilities.json");
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        service = TestService.start(dir, env, Optional.empty(), List.of(), Optional.empty(), Map.of(
                "testMerchantTransports", (MerchantTransports) () -> (HttpTransport) store::send));
    }

    private IntentId propose(String user) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, CATALOG,
                List.of(new ProposedLine(ITEM, 1)), "a board");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        return result.intentId();
    }

    private IntentId proposeFor(String user, BuyerCountry shipTo) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-" + user), user + "@local.ucplab.dev", clock.instant(),
                "test-session");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(auth, CATALOG,
                List.of(new ProposedLine(ITEM, 1)), "a board", Optional.empty(), shipTo);
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

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // Covers: 23.1, 23.2, 24.3, 26.7, 9.2 (a link intent for India is priced by a get_product read for India, in INR,
    // and its approval answer names the country, our own text, outside the snapshot, which shows no link)
    @Test
    void aLinkIntentForIndia_isReadForIndia_andTheApprovalNamesIt() throws IOException {
        store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json")
                .replace("\"currency\": \"USD\"", "\"currency\": \"INR\"")
                .replace("\"currency\":\"USD\"", "\"currency\":\"INR\""));
        start();
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        IntentId id = proposeFor("alice", new BuyerCountry("IN"));

        Map<String, Object> approval = json(api.get(alice, "/api/intents/" + id.value() + "/approval"));

        assertThat(approval).containsEntry("ships_to", Map.of("country", "IN", "name", "India"));
        assertThat(approval.get("snapshot").toString()).doesNotContain("link").doesNotContain("shipsTo");
        assertThat(store.toolCalls()).filteredOn(c -> c.tool().equals("get_product")).singleElement()
                .satisfies(c -> assertThat(((Map<?, ?>) c.arguments().get("catalog")).get("context"))
                        .isEqualTo(Map.of("address_country", "IN", "currency", "INR")));
    }

    // Covers: 7.2 quote_ready (link), hand_off (link), 19, 23.2, 24.3, 26.7, I6, I8, I9, N1 (a catalog entry builds a
    // stack and starts; its approval answer carries completion link, the fixed note and seller {name, host} outside the
    // snapshot, and the snapshot shown has no link; an approval over the API hands the intent off; the owner's intent
    // answer carries the link exactly as handoff_url, merchant {completion, seller} and follow_up {watching: false},
    // never a store_order; the handed_off event holds the host only; only get_product reached the catalog; and the link
    // is in no log line, event, other answer or database column but handoff_url and the approval's snapshot)
    @Test
    void aLinkMerchant_handsOffTheCatalogsLink_andItReachesOnlyTheOwner() throws IOException {
        store.answerText("get_product", McpStore.catalogFixture("global_get_product_us.json")
                .replace("FAKEGSID0001", gsidCanary).replace("FAKECLICK0001", clickCanary));
        start();
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        Session bob = api.signIn("bob");
        IntentId id = propose("alice");

        HttpResponse<String> pending = api.get(alice, "/api/intents/" + id.value() + "/approval");
        assertThat(pending.statusCode()).isEqualTo(200);
        Map<String, Object> approval = json(pending);
        assertThat(approval).containsEntry("completion", "link").containsEntry("note", LINK_NOTE)
                .containsEntry("seller", Map.of("name", "Example Shop A", "host", "www.shop-a.example"));
        assertThat(approval.get("snapshot").toString()).doesNotContain("link").doesNotContain(gsidCanary);
        assertThat(pending.body()).doesNotContain(gsidCanary).doesNotContain(clickCanary);
        String token = (String) approval.get("token");
        HttpResponse<String> decided = api.post(alice, "/api/intents/" + id.value() + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}");
        assertThat(decided.statusCode()).isEqualTo(200);

        Map<String, Object> intent = awaitIntent(api, alice, id, "HANDED_OFF");
        assertThat(intent).containsEntry("state", "HANDED_OFF").containsEntry("handoff_url", link())
                .containsEntry("merchant", Map.of("completion", "link",
                        "seller", Map.of("name", "Example Shop A", "host", "www.shop-a.example")))
                .containsEntry("follow_up", Map.of("watching", false))
                .doesNotContainKey("store_order");
        HttpResponse<String> other = api.get(bob, "/api/intents/" + id.value());
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.body()).doesNotContain(gsidCanary).doesNotContain("shop-a");
        assertThat(store.toolCalls()).extracting(McpStore.Sent::tool).containsOnly("get_product");

        List<IntentEvent> events = service.bean(SqlIntentRepository.class).events(id);
        assertThat(events).filteredOn(e -> e.type().equals("handed_off")).singleElement()
                .satisfies(e -> assertThat(LenientJson.readObject(e.payloadJson()))
                        .isEqualTo(Map.of("host", "www.shop-a.example")));

        LeakEvidence evidence = new LeakEvidence();
        evidence.canary("the link", link(), Allowed.HANDOFF_BODY, "purchase_intent.handoff_url",
                "approval.snapshot_json");
        evidence.canary("the link's gsid", gsidCanary, Allowed.HANDOFF_BODY, "purchase_intent.handoff_url",
                "approval.snapshot_json");
        evidence.canary("the link's click id", clickCanary, Allowed.HANDOFF_BODY, "purchase_intent.handoff_url",
                "approval.snapshot_json");
        evidence.clients(api);
        evidence.database(dir.resolve("service.db"));
        service.close();
        service = null;
        evidence.logs(logs.lines());
        assertThat(evidence.findings()).as("the link where it must not be").isEmpty();
    }
}
