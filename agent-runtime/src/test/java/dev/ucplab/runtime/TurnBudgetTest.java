package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 6.3 [CONTRACT] per turn, 23.1 continuation, 11: the 20,000 token ceiling is a turn's, with a fresh budget each turn;
 * the run record and agent.run's run counts are sums over the run, and agent.run's turn_ counts are the turn's own.
 */
class TurnBudgetTest {

    private static final RunId RUN = new RunId("run-turns");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    /**
     * Turns 1 and 2: one reply each, 14,990 input and 10 output tokens apiece, 30,000 in all. Turn 3: a search at
     * 11,990 + 10, then a reply at 4,990 + 10, 17,000 of its own. Turn 4: a search at 19,990 + 10, which reaches the
     * ceiling on the turn's own count, so its next model call is refused.
     */
    private static final Map<Integer, Usage> USAGE = Map.of(1, new Usage(14_990, 10), 2, new Usage(14_990, 10),
            3, new Usage(11_990, 10), 4, new Usage(4_990, 10), 5, new Usage(19_990, 10));

    /** A catalog with nothing in it: the searches here only spend tool calls. */
    private static ToolPorts ports() {
        return Stubs.ports(Stubs.catalog(List.of(), id -> {
            throw new UnsupportedOperationException();
        }), new Stubs.RecordingOrchestrator());
    }

    private static Map<String, Object> lastRunLine(RunTrace trace) {
        return CanonicalJson.readObject(trace.lines().getLast());
    }

    // Covers: 6.3 per turn, 6.3 tokens, 23.1 continuation, 11 agent.run (earlier turns totalling 30,000 tokens, over the
    // 20,000 ceiling: a later turn still runs, metered on its own, and a turn that reaches 20,000 itself is refused;
    // agent.run shows the run's sums and the turn's own counts, and the record holds the sums)
    @Test
    void earlierTurnsOverTheCeiling_aLaterTurnStillRuns_meteredOnItsOwn() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (c, tools) -> {
            int n = calls.incrementAndGet();
            Usage usage = USAGE.getOrDefault(n, new Usage(10, 10));
            return n == 3 || n == 5
                    ? new ModelTurn("", List.of(new ToolCall("s" + n, "search_catalog",
                            Map.of("query", "mug", "max_results", 1L))), usage)
                    : new ModelTurn("reply " + n, List.of(), usage);
        };
        InMemoryRunRepository runs = new InMemoryRunRepository(CLOCK);
        AgentRuntime runtime = new AgentRuntime(model, "scripted", ports(), runs, AgentBudget.DEFAULT,
                Duration.ofMinutes(5), CLOCK);

        RunSession turn = runtime.start(Stubs.ALICE, RUN, "first", (q, t) -> Optional.empty(), new RunTrace(RUN));
        turn = runtime.resume(Stubs.ALICE, RUN, turn.conversation(), "second", (q, t) -> Optional.empty(),
                new RunTrace(RUN));
        RunRecord afterTwo = runs.find(Stubs.ALICE.user(), RUN).orElseThrow();
        assertThat(afterTwo.inputTokens() + afterTwo.outputTokens()).as("earlier turns, over 20,000").isEqualTo(30_000);

        RunTrace third = new RunTrace(RUN);
        turn = runtime.resume(Stubs.ALICE, RUN, turn.conversation(), "third", (q, t) -> Optional.empty(), third);

        assertThat(turn.outcome()).isEqualTo(new RunOutcome.Done("reply 4", List.of()));
        assertThat(lastRunLine(third)).containsEntry("span", "agent.run")
                .containsEntry("model_calls", 4L).containsEntry("input_tokens", 46_960L)
                .containsEntry("output_tokens", 40L)
                .containsEntry("turn_model_calls", 2L).containsEntry("turn_input_tokens", 16_980L)
                .containsEntry("turn_output_tokens", 20L);
        RunRecord afterThree = runs.find(Stubs.ALICE.user(), RUN).orElseThrow();
        assertThat(afterThree.modelCalls()).isEqualTo(4);
        assertThat(afterThree.inputTokens()).isEqualTo(46_960);

        RunTrace fourth = new RunTrace(RUN);
        RunSession last = runtime.resume(Stubs.ALICE, RUN, turn.conversation(), "fourth", (q, t) -> Optional.empty(),
                fourth);

        assertThat(last.outcome()).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOKENS, List.of()));
        assertThat(lastRunLine(fourth)).containsEntry("turn_model_calls", 1L)
                .containsEntry("turn_input_tokens", 19_990L).containsEntry("turn_output_tokens", 10L)
                .containsEntry("model_calls", 5L).containsEntry("input_tokens", 66_950L);
    }

    // Covers: 11 agent.run, 6.3 per turn (on a run's first turn the turn's counts and the run's sums agree)
    @Test
    void onTheFirstTurn_theTurnsCountsAreTheRunsSums() {
        ModelClient model = (c, tools) -> new ModelTurn("hi", List.of(), new Usage(120, 7));
        AgentRuntime runtime = new AgentRuntime(model, "scripted", ports(), new InMemoryRunRepository(CLOCK),
                AgentBudget.DEFAULT, Duration.ofMinutes(5), CLOCK);
        RunTrace trace = new RunTrace(RUN);

        runtime.start(Stubs.ALICE, RUN, "hello", (q, t) -> Optional.empty(), trace);

        assertThat(lastRunLine(trace)).containsEntry("model_calls", 1L).containsEntry("turn_model_calls", 1L)
                .containsEntry("input_tokens", 120L).containsEntry("turn_input_tokens", 120L)
                .containsEntry("output_tokens", 7L).containsEntry("turn_output_tokens", 7L);
    }
}
