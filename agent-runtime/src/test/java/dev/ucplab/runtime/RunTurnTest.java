package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.BuyerCountry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * One turn of a run (6.2, 6.4, 23.1): after a policy refusal the rest of the turn's tool calls are stopped, and the
 * user's next message starts a turn with every tool again; the run's stored budget is read again before every model
 * call, so the owner's change reaches the hint and the next proposal of the same run.
 */
class RunTurnTest {

    /** The blue mug: 12.00 USD, 12.96 with the lab's tax. */
    private static final Map<String, Object> PROPOSE_MUG = Map.of("merchant_id", RuntimeLab.SHOP.value(),
            "items", List.of(Map.of("item_id", "item_456", "quantity", 1L)), "rationale", "A blue mug.");
    private static final String STOPPED =
            "{\"detail\":\"a purchase was refused by policy: tell the user why, and stop\",\"error\":\"stopped\"}";

    private final List<String> results = new ArrayList<>();
    private final List<String> systemPrompts = new ArrayList<>();

    private static boolean afterTool(Conversation c) {
        return c.messages().getLast() instanceof Message.ToolResult;
    }

    private void record(Conversation c) {
        systemPrompts.add(c.systemPrompt());
        if (c.messages().getLast() instanceof Message.ToolResult r) {
            results.add(r.tool() + " " + r.json());
        }
    }

    private AgentRuntime runtime(RuntimeLab lab, ModelClient model, InMemoryRunRepository runs) {
        return new AgentRuntime(model, "scripted", lab.ports(), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5),
                lab.clock);
    }

    // Covers: 6.2 stopped, 6.4 v7 [REFUSED], P9 (after a P9 refusal, the same turn's search_catalog and ask_user calls
    // are answered "stopped" without running; the user's next message starts a turn where search_catalog runs again)
    @Test
    void afterARefusal_theRestOfTheTurnIsStopped_andTheNextTurnHasEveryTool() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("stopped");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            ModelClient refusedThenBrowse = (c, tools) -> {
                record(c);
                if (!afterTool(c)) {
                    return new ModelTurn("", List.of(new ToolCall("p1", "propose_purchase", PROPOSE_MUG)), Usage.NONE);
                }
                if (((Message.ToolResult) c.messages().getLast()).tool().equals("propose_purchase")) {
                    return new ModelTurn("", List.of(
                            new ToolCall("s1", "search_catalog", Map.of("query", "mug", "max_results", 5L)),
                            new ToolCall("a1", "ask_user", Map.of("question", "Something cheaper?"))), Usage.NONE);
                }
                return new ModelTurn("That mug is over your budget.", List.of(), Usage.NONE);
            };
            RunSession first = runtime(lab, refusedThenBrowse, runs).start(auth, new RunId("run-s"), "A blue mug.",
                    (q, t) -> Optional.of("yes"), new RunTrace(new RunId("run-s")),
                    Optional.of(new Budget(1_000, "USD")), BuyerCountry.US);

            assertThat(results.getFirst()).startsWith("propose_purchase ").contains("policy_P9");
            assertThat(results).contains("search_catalog " + STOPPED, "ask_user " + STOPPED);

            results.clear();
            ModelClient browse = (c, tools) -> {
                record(c);
                return afterTool(c) ? new ModelTurn("Here is one.", List.of(), Usage.NONE)
                        : new ModelTurn("", List.of(new ToolCall("s2", "search_catalog",
                                Map.of("query", "mug", "max_results", 5L))), Usage.NONE);
            };
            runtime(lab, browse, runs).resume(auth, new RunId("run-s"), first.conversation(), "Show me mugs.",
                    (q, t) -> Optional.empty(), new RunTrace(new RunId("run-s")));

            assertThat(results).singleElement().satisfies(r -> assertThat(r).startsWith("search_catalog {\"items\":")
                    .doesNotContain("stopped"));
        }
    }

    // Covers: 6.2 stopped (a turn without a refusal is never stopped: a search after an allowed proposal runs)
    @Test
    void aTurnWithoutARefusal_isNeverStopped() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("allowed");
            ModelClient proposeThenSearch = (c, tools) -> {
                record(c);
                if (!afterTool(c)) {
                    return new ModelTurn("", List.of(new ToolCall("p1", "propose_purchase", PROPOSE_MUG)), Usage.NONE);
                }
                if (((Message.ToolResult) c.messages().getLast()).tool().equals("propose_purchase")) {
                    return new ModelTurn("", List.of(new ToolCall("s1", "search_catalog",
                            Map.of("query", "mug", "max_results", 5L))), Usage.NONE);
                }
                return new ModelTurn("Approve the mug.", List.of(), Usage.NONE);
            };
            runtime(lab, proposeThenSearch, new InMemoryRunRepository(lab.clock)).start(auth, new RunId("run-a"),
                    "A blue mug.", (q, t) -> Optional.empty(), new RunTrace(new RunId("run-a")),
                    Optional.of(new Budget(5_000, "USD")), BuyerCountry.US);

            assertThat(results.getFirst()).contains("AWAITING_APPROVAL");
            assertThat(results.get(1)).startsWith("search_catalog {\"items\":");
        }
    }

    // Covers: 23.1 PATCH budget, 6.4 hint, P9 (the owner changes the run's budget between two model calls of one turn:
    // the next call's hint names the new budget, and the proposal made after it is under the new budget)
    @Test
    void theOwnersBudgetChange_reachesTheNextModelCall_andTheNextProposal() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("changed");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            RunId id = new RunId("run-b");
            ModelClient changeThenPropose = (c, tools) -> {
                record(c);
                if (systemPrompts.size() == 1) {
                    assertThat(runs.setBudget(auth.user(), id, Optional.of(new Budget(1_000, "USD")))).isTrue();
                    return new ModelTurn("", List.of(new ToolCall("s1", "search_catalog",
                            Map.of("query", "mug", "max_results", 5L))), Usage.NONE);
                }
                if (systemPrompts.size() == 2) {
                    return new ModelTurn("", List.of(new ToolCall("p1", "propose_purchase", PROPOSE_MUG)), Usage.NONE);
                }
                return new ModelTurn("Done.", List.of(), Usage.NONE);
            };
            runtime(lab, changeThenPropose, runs).start(auth, id, "A blue mug.", (q, t) -> Optional.empty(),
                    new RunTrace(id), Optional.of(new Budget(5_000, "USD")), BuyerCountry.US);

            assertThat(systemPrompts.get(0)).contains("$50.00");
            assertThat(systemPrompts.get(1)).contains("$10.00").doesNotContain("$50.00");
            assertThat(lab.intentsOf(auth.user())).singleElement()
                    .satisfies(i -> assertThat(i.budget()).contains(new Budget(1_000, "USD")));
            assertThat(results.get(1)).startsWith("propose_purchase ").contains("policy_P9");
        }
    }
}
