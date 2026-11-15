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
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The approver channel and the lab routes the console uses, with two merchants (m6a C9; DESIGN 9.3 L4, I2, I3, 23.2, 23.3,
 * 24.2): the approval GET names the merchant of the stored snapshot, a run with two proposals gives two approvals each with
 * its own merchant and token, and deciding one leaves the other pending.
 */
class MultiApprovalApiTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");

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

    /** A model that proposes at merchant A, then at merchant B, then says it is done: a basket across two merchants. */
    private static ModelClient basketModel() {
        return (conversation, tools) -> {
            long results = conversation.messages().stream().filter(m -> m instanceof Message.ToolResult).count();
            if (results == 0) {
                return propose("p1", "merchant-a", "item_456");
            }
            if (results == 1) {
                return propose("p2", "merchant-b", "b_mug_01");
            }
            return new ModelTurn("Two proposals are waiting for your approval.", List.of(), Usage.NONE);
        };
    }

    private static ModelTurn propose(String callId, String merchant, String item) {
        return new ModelTurn("", List.of(new ToolCall(callId, "propose_purchase", Map.of("merchant_id", merchant,
                "items", List.of(Map.of("item_id", item, "quantity", 1L)), "rationale", "a mug at each"))), Usage.NONE);
    }

    private void start(Map<String, Object> singletons) throws IOException {
        service = TestService.startMerchants(dir, Map.of(), Optional.empty(), "active", true, singletons, 0);
        api = new ApiClient(service.port());
    }

    private IntentId propose(MerchantId merchant, String item) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(), "s");
        return service.bean(DefaultOrchestrator.class)
                .propose(auth, merchant, List.of(new ProposedLine(item, 1)), "console test").intentId();
    }

    private Map<String, Object> approval(Session s, String id) {
        return json(api.get(s, "/api/intents/" + id + "/approval"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(Object map) {
        return map instanceof Map<?, ?> m && m.containsKey("snapshot") ? (Map<String, Object>) m.get("snapshot")
                : (Map<String, Object>) map;
    }

    private HttpResponse<String> decide(Session s, String id, String token, String decision) {
        return api.post(s, "/api/intents/" + id + "/approval", "{\"token\":\"" + token + "\",\"decision\":\"" + decision + "\"}");
    }

    private String runStatus(Session s, String runId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> run = json(api.get(s, "/api/runs/" + runId));
        while ("running".equals(run.get("status")) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            run = json(api.get(s, "/api/runs/" + runId));
        }
        return String.valueOf(run.get("status"));
    }

    // Covers: 9.3 L4, I2, I3, 23.2 (the approval GET names the merchant of the stored snapshot, so the card's heading never
    // comes from the agent's words; the snapshot's other fields are as before)
    @Test
    void approvalGet_carriesTheStoredSnapshotsMerchant() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");
        IntentId atA = propose(A, "item_456");
        IntentId atB = propose(B, "b_mug_01");

        Map<String, Object> a = snapshot(approval(alice, atA.value()));
        Map<String, Object> b = snapshot(approval(alice, atB.value()));

        assertThat(a.get("merchant_id")).isEqualTo("merchant-a");
        assertThat(b.get("merchant_id")).isEqualTo("merchant-b");
        assertThat(a.keySet()).containsExactlyInAnyOrder("merchant_id", "currency", "total_minor", "lines");
    }

    // Covers: 9.3 L4, I3, I11, 23.2, S22 (a run with two proposals gives two approval GETs, each with its own merchant and
    // its own token; approving one completes it at its merchant and leaves the other pending, with no order at its merchant)
    @Test
    void aRunWithTwoProposals_givesTwoApprovals_andApprovingOneLeavesTheOtherPending() throws IOException {
        start(Map.of("testModel", basketModel()));
        Session alice = api.signIn("alice");
        Map<String, Object> started = json(api.post(alice, "/api/runs", "{\"message\":\"a mug from each shop\"}"));
        String runId = (String) started.get("run_id");

        assertThat(runStatus(alice, runId)).isEqualTo("waiting_for_approval");
        List<?> ids = (List<?>) json(api.get(alice, "/api/runs/" + runId)).get("intent_ids");
        assertThat(ids).hasSize(2);
        Map<String, Object> first = approval(alice, (String) ids.get(0));
        Map<String, Object> second = approval(alice, (String) ids.get(1));
        assertThat(((Map<?, ?>) first.get("snapshot")).get("merchant_id")).isEqualTo("merchant-a");
        assertThat(((Map<?, ?>) second.get("snapshot")).get("merchant_id")).isEqualTo("merchant-b");
        assertThat(first.get("token")).isNotEqualTo(second.get("token"));

        // A token is for its own approval: the first card's token does not decide the second intent.
        assertThat(decide(alice, (String) ids.get(1), (String) first.get("token"), "approve").statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(decide(alice, (String) ids.get(1), (String) second.get("token"), "approve").statusCode()).isEqualTo(200);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (service.merchantB.orders().isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertThat(service.merchantB.orders()).hasSize(1);
        assertThat(service.merchant.orders()).isEmpty();
        assertThat(json(api.get(alice, "/api/intents/" + ids.get(0)))).containsEntry("state", "AWAITING_APPROVAL");
        assertThat(api.get(alice, "/api/intents/" + ids.get(0) + "/approval").statusCode()).isEqualTo(200);
    }

    // Covers: 23.3, 24.3, D7 (the console's merchant selector and counters come from one lab route: each configured merchant
    // with its status and, when SUSPENDED, the fixed reason; it needs a session)
    @Test
    void labMerchants_listsEachMerchantWithItsStatus() throws IOException {
        service = TestService.startMerchants(dir, Map.of(), Optional.empty(), "suspended", true);
        api = new ApiClient(service.port());
        assertThat(api.get("/api/lab/merchants").statusCode()).isEqualTo(401);
        Session alice = api.signIn("alice");

        List<?> merchants = (List<?>) json(api.get(alice, "/api/lab/merchants")).get("merchants");

        assertThat(merchants).hasSize(2);
        Map<String, Object> a = snapshot(merchants.get(0));
        Map<String, Object> b = snapshot(merchants.get(1));
        assertThat(a.get("merchant_id")).isEqualTo("merchant-a");
        assertThat(a.get("status")).isEqualTo("ACTIVE");
        assertThat(a).doesNotContainKey("reason");
        assertThat(b.get("merchant_id")).isEqualTo("merchant-b");
        assertThat(b.get("status")).isEqualTo("SUSPENDED");
        assertThat(b.get("reason")).isEqualTo("suspended_in_file");
        assertThat(a.keySet()).doesNotContain("url", "lab_url", "token_ref");
    }

    // Covers: 23.3, 24.3 (a merchant the console names that is not configured, or not a string, is 422 on faults, and nothing
    // is installed)
    @Test
    void anUnknownMerchantFromTheConsole_is422() throws IOException {
        start(Map.of());
        Session alice = api.signIn("alice");

        for (String body : List.of("{\"preset\":\"merchant_down\",\"merchant_id\":\"merchant-z\"}",
                "{\"preset\":\"merchant_down\",\"merchant_id\":7}", "{\"preset\":\"merchant_down\",\"merchant_id\":\"\"}")) {
            HttpResponse<String> r = api.send("PUT", "/api/lab/faults", Optional.of(body), "Cookie",
                    "ucplab_session=" + alice.cookie(), "X-CSRF-Token", alice.csrf(), "Content-Type", "application/json");
            assertThat(r.statusCode()).as(body).isEqualTo(422);
        }
        assertThat(json(api.get(alice, "/api/lab/orders?merchant_id=merchant-a"))).containsKey("orders");
    }
}
