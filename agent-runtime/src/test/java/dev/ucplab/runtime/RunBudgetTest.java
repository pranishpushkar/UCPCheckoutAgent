package dev.ucplab.runtime;

import dev.ucplab.core.money.MoneyFormat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The user's budget in the runtime (6.2, 6.4, P9): the tool gives the orchestrator the run's budget, never anything the
 * model said; the model sees it as a [BUDGET] hint; a P9 denial tells the agent the budget and the quoted total.
 */
class RunBudgetTest {

    private static final String HINT = "[BUDGET] The user's budget for this run is $10.00. Propose only items whose total"
            + " fits it; a quote above it is refused.";

    /** Proposes one blue mug (1200 USD minor units at the lab merchant) on its first turn, then says it is done. */
    private static final class ProposeOnce implements ModelClient {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<String> results = new CopyOnWriteArrayList<>();

        @Override
        public ModelTurn generate(Conversation c, List<ToolSpec> tools) {
            prompts.add(c.systemPrompt());
            List<Message> messages = c.messages();
            if (messages.getLast() instanceof Message.ToolResult r) {
                results.add(r.json());
                return new ModelTurn("None fits your budget.", List.of(), Usage.NONE);
            }
            return new ModelTurn("", List.of(new ToolCall("c1", "propose_purchase", Map.of(
                    "merchant_id", RuntimeLab.SHOP.value(),
                    "items", List.of(Map.of("item_id", "item_456", "quantity", 1L)),
                    "rationale", "A blue mug."))), Usage.NONE);
        }
    }

    // Covers: P9, 6.2, 6.2 amounts as text, 6.4, 23.1 (a run under a 10.00 USD budget: the model's context has the hint
    // after the unchanged prompt, the intent gets the run's budget, the quote above it ends FAILED_NOT_CHARGED with
    // policy_P9, and the tool result names the budget and the quoted total, as text and as numbers, so the agent can
    // say none fits)
    @Test
    void aQuoteAboveTheRunsBudget_isRefused_andTheToolResultSaysWhy() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("budgeted");
            ProposeOnce model = new ProposeOnce();
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            AgentRuntime runtime = new AgentRuntime(model, "scripted", lab.ports(), runs, AgentBudget.DEFAULT,
                    Duration.ofMinutes(5), lab.clock);

            runtime.start(auth, new RunId("run-b"), "A mug under $10.", (q, t) -> Optional.empty(),
                    new RunTrace(new RunId("run-b")), Optional.of(new Budget(1_000, "USD")));

            assertThat(model.prompts).isNotEmpty().allSatisfy(p -> assertThat(p)
                    .isEqualTo(SystemPrompt.text() + "\n\n" + HINT));
            PurchaseIntent intent = lab.intentsOf(auth.user()).getFirst();
            assertThat(intent.budget()).contains(new Budget(1_000, "USD"));
            assertThat(intent.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(intent.failureReason()).contains("policy_P9");
            assertThat(lab.approvalsOf(auth.user())).isEmpty();
            Map<String, Object> result = CanonicalJson.readObject(model.results.getFirst());
            assertThat(result).containsEntry("state", "FAILED_NOT_CHARGED")
                    .containsEntry("failure_reason", "policy_P9")
                    .containsEntry("reason", "over your budget of $10.00 (P9)");
            assertThat(result.get("quoted")).isInstanceOfSatisfying(Map.class, q -> assertThat(q)
                    .containsEntry("currency", "USD").containsKey("total_minor"));
            assertThat(((Number) ((Map<?, ?>) result.get("quoted")).get("total_minor")).longValue()).isGreaterThan(1_000);
            // 6.2: the budget as numbers too, so a model names both amounts without reading our text back; and each
            // amount as text beside its minor units (6.2 amounts as text, 24.3), for the agent to write as it is.
            assertThat(result).containsEntry("budget", Map.of("amount_minor", 1_000L, "amount", "$10.00",
                    "currency", "USD"));
            long quotedMinor = ((Number) ((Map<?, ?>) result.get("quoted")).get("total_minor")).longValue();
            assertThat(result.get("quoted")).isEqualTo(Map.of("currency", "USD", "total_minor", quotedMinor,
                    "total", MoneyFormat.format(quotedMinor, "USD")));
            assertThat(runs.find(auth.user(), new RunId("run-b")).orElseThrow().budget())
                    .contains(new Budget(1_000, "USD"));
            assertThat(lab.merchant.orders()).isEmpty();
        }
    }

    // Covers: P9, 6.4, 23.1 (a continuation keeps the run's stored budget: the hint is rebuilt from the record, and the
    // next proposal is held to it)
    @Test
    void aContinuation_keepsTheRunsBudget() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("continued");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            ModelClient hello = (c, t) -> new ModelTurn("What would you like?", List.of(), Usage.NONE);
            RunSession first = new AgentRuntime(hello, "scripted", lab.ports(), runs, AgentBudget.DEFAULT,
                    Duration.ofMinutes(5), lab.clock).start(auth, new RunId("run-c"), "hello", (q, t) -> Optional.empty(),
                    new RunTrace(new RunId("run-c")), Optional.of(new Budget(1_000, "USD")));

            ProposeOnce model = new ProposeOnce();
            new AgentRuntime(model, "scripted", lab.ports(), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5), lab.clock)
                    .resume(auth, new RunId("run-c"), first.conversation(), "a blue mug", (q, t) -> Optional.empty(),
                            new RunTrace(new RunId("run-c")));

            assertThat(model.prompts).isNotEmpty().allSatisfy(p -> assertThat(p).endsWith(HINT));
            PurchaseIntent intent = lab.intentsOf(auth.user()).getFirst();
            assertThat(intent.budget()).contains(new Budget(1_000, "USD"));
            assertThat(intent.failureReason()).contains("policy_P9");
        }
    }

    // Covers: 6.4 (a run without a budget has no hint: the prompt is exactly the versioned system prompt)
    @Test
    void aRunWithoutABudget_hasNoHint() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            ProposeOnce model = new ProposeOnce();
            new AgentRuntime(model, "scripted", lab.ports(), new InMemoryRunRepository(lab.clock), AgentBudget.DEFAULT,
                    Duration.ofMinutes(5), lab.clock).run(lab.auth("plain"), new RunId("run-p"), "A mug.",
                    (q, t) -> Optional.empty());

            assertThat(model.prompts).isNotEmpty().allSatisfy(p -> assertThat(p).isEqualTo(SystemPrompt.text()));
            assertThat(SystemPrompt.text(Optional.empty())).isEqualTo(SystemPrompt.text());
        }
    }
}
