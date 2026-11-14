package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 6.3, FR2: a budget breach after {@code propose_purchase} leaves the intent as it is. It stays AWAITING_APPROVAL for
 * the user to decide, and is listed in the run's outcome and record.
 */
class BudgetAfterProposeTest {

    // Covers: 6.3, FR2, 23.1 (the tool-call limit after a proposal: the intent remains AWAITING_APPROVAL and is listed
    // in the run's intents)
    @Test
    void toolLimitAfterPropose_intentRemainsAwaitingApprovalAndListed() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("after-propose");
            String status = "{\"tool\":\"get_purchase_status\",\"args\":{\"intent_id\":\"${result.intent_id}\"}}";
            ScriptedModel model = ScriptedModel.parse("{\"name\":\"budget-after-propose\",\"turns\":["
                    + "{\"call\":[{\"tool\":\"search_catalog\",\"args\":{\"query\":\"blue mug\",\"max_results\":3}}]},"
                    + "{\"when\":{\"tool\":\"search_catalog\"},\"call\":[{\"tool\":\"propose_purchase\",\"args\":{"
                    + "\"merchant_id\":\"${result.items[0].merchant_id}\",\"items\":[{\"item_id\":\"item_456\","
                    + "\"quantity\":1}],\"rationale\":\"A blue mug.\"}}]},"
                    + "{\"when\":{\"tool\":\"propose_purchase\",\"resultMatches\":\"AWAITING_APPROVAL\"},\"call\":["
                    + String.join(",", Collections.nCopies(7, status)) + "]},"
                    + "{\"text\":\"never\"}]}");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            AgentRuntime runtime = new AgentRuntime(model, "scripted", lab.ports(), runs, AgentBudget.DEFAULT,
                    Duration.ofMinutes(5), lab.clock);

            RunOutcome outcome = runtime.run(auth, new RunId("run-after"), "Buy a blue mug.", (q, t) -> Optional.empty());

            PurchaseIntent intent = lab.intentsOf(auth.user()).getFirst();
            assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOOL_CALLS, List.of(intent.id())));
            assertThat(lab.intents.findForSystem(intent.id()).orElseThrow().state())
                    .isEqualTo(IntentState.AWAITING_APPROVAL);
            assertThat(lab.intentsOf(auth.user())).hasSize(1);
            RunRecord record = runs.find(auth.user(), new RunId("run-after")).orElseThrow();
            assertThat(record.intentIds()).containsExactly(intent.id());
            assertThat(record.outcome()).contains("budget_exceeded");
            assertThat(record.toolCalls()).isEqualTo(8);
            assertThat(lab.merchant.orders()).as("nothing was bought").isEmpty();
        }
    }
}
