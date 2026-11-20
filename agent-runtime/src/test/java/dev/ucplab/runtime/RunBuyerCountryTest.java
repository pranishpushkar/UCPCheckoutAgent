package dev.ucplab.runtime;

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
import dev.ucplab.core.intent.BuyerCountry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The run's buyer country in the runtime (23.1, 6.2): the run record keeps it, the tools get it from the run, and each
 * intent the run proposes is for it, never for anything the model said; a continuation keeps it.
 */
class RunBuyerCountryTest {

    private static final BuyerCountry INDIA = new BuyerCountry("IN");

    /** Proposes one blue mug on its first turn, then says it is done. */
    private static final ModelClient PROPOSE_ONCE = (Conversation c, List<ToolSpec> tools) -> {
        if (c.messages().getLast() instanceof Message.ToolResult) {
            return new ModelTurn("Done.", List.of(), Usage.NONE);
        }
        return new ModelTurn("", List.of(new ToolCall("c1", "propose_purchase", Map.of(
                "merchant_id", RuntimeLab.SHOP.value(),
                "items", List.of(Map.of("item_id", "item_456", "quantity", 1L)),
                "rationale", "A blue mug."))), Usage.NONE);
    };

    // Covers: 23.1, 6.2, 10 (V13) (a run for India: its record keeps the country, and its proposal's intent is for India)
    @Test
    void aRunForIndia_keepsTheCountry_andItsIntentIsForIt() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("india");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            new AgentRuntime(PROPOSE_ONCE, "scripted", lab.ports(), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5),
                    lab.clock).start(auth, new RunId("run-i"), "A mug.", (q, t) -> Optional.empty(),
                    new RunTrace(new RunId("run-i")), Optional.empty(), INDIA);

            assertThat(runs.find(auth.user(), new RunId("run-i")).orElseThrow().shipTo()).isEqualTo(INDIA);
            assertThat(lab.intentsOf(auth.user())).singleElement()
                    .satisfies(i -> assertThat(i.shipTo()).isEqualTo(INDIA));
        }
    }

    // Covers: 23.1 (a continuation keeps the run's stored country: the next proposal is for it)
    @Test
    void aContinuation_keepsTheRunsCountry() throws Exception {
        try (RuntimeLab lab = new RuntimeLab()) {
            AuthContext auth = lab.auth("continued");
            InMemoryRunRepository runs = new InMemoryRunRepository(lab.clock);
            ModelClient hello = (c, t) -> new ModelTurn("What would you like?", List.of(), Usage.NONE);
            RunSession first = new AgentRuntime(hello, "scripted", lab.ports(), runs, AgentBudget.DEFAULT,
                    Duration.ofMinutes(5), lab.clock).start(auth, new RunId("run-c"), "hello", (q, t) -> Optional.empty(),
                    new RunTrace(new RunId("run-c")), Optional.empty(), INDIA);

            new AgentRuntime(PROPOSE_ONCE, "scripted", lab.ports(), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5),
                    lab.clock).resume(auth, new RunId("run-c"), first.conversation(), "a blue mug",
                    (q, t) -> Optional.empty(), new RunTrace(new RunId("run-c")));

            assertThat(lab.intentsOf(auth.user())).singleElement()
                    .satisfies(i -> assertThat(i.shipTo()).isEqualTo(INDIA));
            assertThat(runs.find(auth.user(), new RunId("run-c")).orElseThrow().shipTo()).isEqualTo(INDIA);
        }
    }
}
