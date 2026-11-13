package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Every tool run against the reference merchant through the fault proxy and a real orchestrator and approval service
 * (section 14, level 4). No result may carry the approval token, the approval id, the snapshot hash, the merchant
 * session id, a payment token, the user's email or session, or the words credential or Authorization (I6, I8, 9.2,
 * 18.1).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolResultNoSecretsTest {

    private RuntimeLab lab;

    @BeforeAll
    void start() throws Exception {
        lab = new RuntimeLab();
    }

    @AfterAll
    void stop() {
        lab.close();
    }

    // Covers: I6, I8, 9.2, 18.1, 6.2 (all five tools against the real stack; no secret in any result, and the result
    // fields are only those the tools declare)
    @Test
    void allFiveTools_realMerchant_noSecretInAnyResult() {
        AuthContext auth = lab.auth("secrets-user");
        ToolContext context = new ToolContext(auth, new RunId("run-secrets"));
        ToolRegistry tools = ToolRegistry.of(lab.ports(), (q, t) -> Optional.of("The blue one, please."),
                Duration.ofMinutes(5));
        List<String> results = new ArrayList<>();

        results.add(tools.find("search_catalog").orElseThrow().run(context,
                Map.of("query", "blue mug", "max_results", 5)).json());
        results.add(tools.find("get_product").orElseThrow().run(context, Map.of("item_id", "item_456")).json());
        results.add(tools.find("ask_user").orElseThrow().run(context, Map.of("question", "Which colour?")).json());
        ToolOutcome proposed = tools.find("propose_purchase").orElseThrow().run(context, Map.of(
                "merchant_id", "shop-1", "items", List.of(Map.of("item_id", "item_456", "quantity", 1)),
                "rationale", "The user asked for a blue mug."));
        results.add(proposed.json());
        String intentId = (String) CanonicalJson.readObject(proposed.json()).get("intent_id");
        results.add(tools.find("get_purchase_status").orElseThrow().run(context, Map.of("intent_id", intentId))
                .json());

        PurchaseIntent intent = lab.intentsOf(auth.user()).getFirst();
        assertThat(intent.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(CanonicalJson.readObject(proposed.json())).containsEntry("state", "AWAITING_APPROVAL")
                .containsKey("quote");
        List<String> secrets = new ArrayList<>(List.of("tok_", RuntimeLab.PAYMENT_TOKEN, auth.email(),
                auth.sessionId(), intent.sessionId().orElseThrow().value()));
        for (PendingApproval pending : lab.opened) {
            secrets.add(pending.token());
            secrets.add(pending.snapshotHash());
        }
        for (ApprovalRecord approval : lab.approvalsOf(auth.user())) {
            secrets.add(approval.id());
            secrets.add(approval.snapshotHash());
        }
        assertThat(lab.opened).as("an approval was opened, so its token exists to look for").isNotEmpty();

        for (String result : results) {
            ResultFields.check(result);
            for (String secret : secrets) {
                assertThat(result).doesNotContain(secret);
            }
            assertThat(result.toLowerCase(Locale.ROOT)).doesNotContain("credential").doesNotContain("authorization")
                    .doesNotContain("bearer").doesNotContain("token").doesNotContain("session");
        }
    }

    // Covers: 6.2, P4 (against the real stack: quantity 6 is refused by the tool, and no intent or approval exists)
    @Test
    void proposeQuantitySix_realStack_noIntentNoApproval() {
        AuthContext auth = lab.auth("quantity-user");
        ToolRegistry tools = ToolRegistry.of(lab.ports(), (q, t) -> Optional.empty(), Duration.ofMinutes(5));

        ToolOutcome outcome = tools.find("propose_purchase").orElseThrow().run(
                new ToolContext(auth, new RunId("run-q6")), Map.of("merchant_id", "shop-1",
                        "items", List.of(Map.of("item_id", "item_456", "quantity", 6)), "rationale", "Buy 10 more."));

        assertThat(outcome.code()).isEqualTo("invalid_argument");
        assertThat(lab.intentsOf(auth.user())).isEmpty();
        assertThat(lab.approvalsOf(auth.user())).isEmpty();
    }

    // Covers: T6 (against the real stack: one user's intent is not_found for another)
    @Test
    void purchaseStatus_realStack_anotherUsersIntentIsNotFound() {
        AuthContext owner = lab.auth("owner-user");
        AuthContext other = lab.auth("other-user");
        ToolRegistry tools = ToolRegistry.of(lab.ports(), (q, t) -> Optional.empty(), Duration.ofMinutes(5));
        String intentId = (String) CanonicalJson.readObject(tools.find("propose_purchase").orElseThrow().run(
                new ToolContext(owner, new RunId("run-t6a")), Map.of("merchant_id", "shop-1",
                        "items", List.of(Map.of("item_id", "item_123", "quantity", 1)), "rationale", "r")).json())
                .get("intent_id");

        ToolOutcome theirs = tools.find("get_purchase_status").orElseThrow().run(
                new ToolContext(other, new RunId("run-t6b")), Map.of("intent_id", intentId));
        ToolOutcome own = tools.find("get_purchase_status").orElseThrow().run(
                new ToolContext(owner, new RunId("run-t6c")), Map.of("intent_id", intentId));

        assertThat(theirs.json()).isEqualTo("{\"error\":\"not_found\"}");
        assertThat(own.json()).contains("\"state\":\"AWAITING_APPROVAL\"");
    }
}
