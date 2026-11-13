package dev.ucplab.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.agent.InMemoryRunRepository;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.ToolEvent;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final RunId RUN = new RunId("run-1");
    private static final String SEARCH = "{\"tool\":\"search_catalog\",\"args\":{\"query\":\"mug\",\"max_results\":1}}";
    private static final UserChannel SILENT = (q, t) -> Optional.empty();

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final Stubs.RecordingOrchestrator orchestrator = new Stubs.RecordingOrchestrator();
    /** Every catalog call, in order. */
    private final List<String> catalogCalls = new CopyOnWriteArrayList<>();
    /** How far a search moves the clock. */
    private Duration searchTakes = Duration.ZERO;

    private final CatalogGateway catalog = new CatalogGateway() {
        @Override
        public CallOutcome<CatalogPage> search(CatalogSearch request) {
            catalogCalls.add("search " + request.query());
            clock.advance(searchTakes);
            return new CallOutcome.Ok<>(200, new CatalogPage(List.of(Stubs.product("item_456", "Blue Mug", "A mug",
                    1200)), false), List.of());
        }

        @Override
        public CallOutcome<List<CatalogProduct>> lookup(List<String> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CallOutcome<CatalogProduct> product(String id) {
            catalogCalls.add("product " + id);
            return new CallOutcome.Ok<>(200, Stubs.product(id, "Blue Mug", "A mug", 1200), List.of());
        }
    };

    private AgentRuntime runtime(ModelClient model) {
        return runtime(model, AgentBudget.DEFAULT);
    }

    private AgentRuntime runtime(ModelClient model, AgentBudget budget) {
        return new AgentRuntime(model, "scripted", Stubs.ports(catalog, orchestrator), runs, budget,
                Duration.ofMinutes(5), clock);
    }

    private static ScriptedModel script(String turns) {
        return ScriptedModel.parse("{\"name\":\"t\",\"turns\":[" + turns + "]}");
    }

    private static String calls(int n) {
        return "{\"call\":[" + String.join(",", java.util.Collections.nCopies(n, SEARCH)) + "]}";
    }

    private RunRecord record() {
        return runs.find(Stubs.ALICE.user(), RUN).orElseThrow();
    }

    // Covers: 6.5, FR2, m5-plan section 4 (a turn's tool calls run in order; a turn with no call ends the run as
    // Done; the record is stored under the run's user with names, counts and codes)
    @Test
    void run_toolCallsInOrderThenText_isDoneAndRecorded() {
        ScriptedModel model = script("""
                {"call": [{"tool": "search_catalog", "args": {"query": "first", "max_results": 1}},
                          {"tool": "get_product", "args": {"item_id": "item_456"}},
                          {"tool": "search_catalog", "args": {"query": "third", "max_results": 1}}]},
                {"text": "Here is what I found."}""");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "find me a mug", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.Done("Here is what I found.", List.of()));
        assertThat(catalogCalls).containsExactly("search first", "product item_456", "search third");
        RunRecord record = record();
        assertThat(record.outcome()).contains("done");
        assertThat(record.model()).isEqualTo("scripted/v1");
        assertThat(record.modelCalls()).isEqualTo(2);
        assertThat(record.toolCalls()).isEqualTo(3);
        assertThat(record.toolEvents()).extracting(ToolEvent::tool)
                .containsExactly("search_catalog", "get_product", "search_catalog");
        assertThat(record.toolEvents()).extracting(ToolEvent::summary).containsOnly("ok");
        assertThat(runs.find(new UserId("bob"), RUN)).as("T6: another user's run reads as empty").isEmpty();
    }

    // Covers: 6.3 (the seventh model call is never made: BudgetExceeded(MODEL_CALLS))
    @Test
    void run_modelCallLimit_isBudgetExceeded() {
        ScriptedModel model = script(String.join(",", java.util.Collections.nCopies(7, calls(1))));

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.MODEL_CALLS, List.of()));
        assertThat(record().modelCalls()).isEqualTo(6);
        assertThat(record().outcome()).contains("budget_exceeded");
    }

    // Covers: 6.3 (a turn asking for nine tool calls runs eight; the ninth is refused unexecuted)
    @Test
    void run_toolCallLimit_isBudgetExceededAndTheRestNotRun() {
        RunOutcome outcome = runtime(script(calls(9))).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOOL_CALLS, List.of()));
        assertThat(catalogCalls).hasSize(8);
        assertThat(record().toolCalls()).isEqualTo(8);
    }

    // Covers: 6.3 (past 30 s of wall clock the next call is refused)
    @Test
    void run_wallClockLimit_isBudgetExceeded() {
        searchTakes = Duration.ofSeconds(31);

        RunOutcome outcome = runtime(script(calls(1) + ",{\"text\":\"never\"}")).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.WALL_CLOCK, List.of()));
        assertThat(record().modelCalls()).isEqualTo(1);
    }

    // Covers: 6.3 (one turn may overshoot the token ceiling; the next call, here that turn's own tool call, is
    // refused)
    @Test
    void run_tokenCeiling_oneTurnOvershootsThenRefused() {
        ScriptedModel model = script(calls(1) + ",{\"call\":[" + SEARCH + "],\"usage\":{\"inputTokens\":24000,"
                + "\"outputTokens\":1000}},{\"text\":\"never\"}");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.BudgetExceeded(BudgetLimit.TOKENS, List.of()));
        assertThat(catalogCalls).as("only the first turn's call ran").hasSize(1);
        assertThat(record().modelCalls()).isEqualTo(2);
        assertThat(record().inputTokens()).isEqualTo(24000);
        assertThat(record().outputTokens()).isEqualTo(1000);
    }

    // Covers: 6.2 (ask_user with no answer ends the run as UserTimedOut, and no further tool call is made)
    @Test
    void run_askUserTimeout_isUserTimedOutAndNoFurtherCall() {
        List<Duration> timeouts = new CopyOnWriteArrayList<>();
        ScriptedModel model = script("{\"call\":[{\"tool\":\"ask_user\",\"args\":{\"question\":\"Which colour?\"}},"
                + SEARCH + "]},{\"text\":\"never\"}");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", (q, t) -> {
            timeouts.add(t);
            return Optional.empty();
        });

        assertThat(outcome).isEqualTo(new RunOutcome.UserTimedOut("Which colour?"));
        assertThat(catalogCalls).isEmpty();
        assertThat(timeouts).containsExactly(Duration.ofMinutes(5));
        assertThat(record().outcome()).contains("user_timeout");
        assertThat(record().modelCalls()).isEqualTo(1);
    }

    // Covers: 6.3 (ten minutes waiting for the user's answer use none of the 30 s)
    @Test
    void run_userWaitOfTenMinutes_isNotWallClock() {
        ScriptedModel model = script("{\"call\":[{\"tool\":\"ask_user\",\"args\":{\"question\":\"Which colour?\"}}]},"
                + calls(1) + ",{\"text\":\"done\"}");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", (q, t) -> {
            clock.advance(Duration.ofMinutes(10));
            return Optional.of("blue");
        });

        assertThat(outcome).isEqualTo(new RunOutcome.Done("done", List.of()));
        assertThat(catalogCalls).hasSize(1);
    }

    // Covers: 6.5, m5-plan section 4 (a model error ends the run as Failed, with no retry)
    @Test
    void run_modelError_isFailedWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient failing = (conversation, tools) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("503 from the model with secret detail");
        };

        RunOutcome outcome = runtime(failing).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.Failed("model_error"));
        assertThat(calls).hasValue(1);
        assertThat(record().outcome()).contains("failed");
    }

    // Covers: m5-plan section 4 (a tool that fails unexpectedly ends the run as Failed; the message is not kept)
    @Test
    void run_toolError_isFailed() {
        ModelClient model = script("{\"call\":[{\"tool\":\"get_purchase_status\",\"args\":{\"intent_id\":\"i-1\"}}]},"
                + "{\"text\":\"never\"}");
        AgentRuntime runtime = new AgentRuntime(model, "scripted", Stubs.ports(catalog, new Stubs.RecordingOrchestrator() {
            @Override
            public IntentStatus status(dev.ucplab.core.identity.AuthContext auth, IntentId id) {
                throw new IllegalStateException("database down");
            }
        }), runs, AgentBudget.DEFAULT, Duration.ofMinutes(5), clock);

        assertThat(runtime.run(Stubs.ALICE, RUN, "m", SILENT)).isEqualTo(new RunOutcome.Failed("tool_error"));
    }

    // Covers: 6.2, I6 (a tool name outside the five gets an error result, is recorded as unknown, and the run goes on)
    @Test
    void run_unknownTool_isAnErrorResultAndTheRunGoesOn() {
        ScriptedModel model = ScriptedModel.parse("""
                {"name": "unknown",
                 "turns": [{"call": [{"tool": "approve_purchase", "args": {"intent_id": "i-1"}}]},
                           {"when": {"tool": "approve_purchase", "resultMatches": "unknown_tool"}, "text": "cannot"}]}""");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.Done("cannot", List.of()));
        assertThat(record().toolEvents()).singleElement().satisfies(e -> {
            assertThat(e.tool()).isEqualTo("unknown");
            assertThat(e.summary()).isEqualTo("unknown_tool");
        });
        assertThat(orchestrator.calls).isEmpty();
    }

    // Covers: FR2, 23.1 (a run that proposed an intent awaiting approval is recorded as waiting_for_approval, and its
    // intents are listed)
    @Test
    void run_proposalAwaitingApproval_isWaitingForApproval() {
        IntentId intent = new IntentId("intent-1");
        orchestrator.proposal = new ProposalResult(intent, IntentState.AWAITING_APPROVAL, Optional.empty());
        orchestrator.statuses = Map.of(intent, new IntentStatus(intent, IntentState.AWAITING_APPROVAL,
                Optional.empty(), Optional.empty()));
        ScriptedModel model = script("""
                {"call": [{"tool": "propose_purchase", "args": {"merchant_id": "shop-1",
                           "items": [{"item_id": "item_456", "quantity": 1}], "rationale": "r"}}]},
                {"text": "Please approve."}""");

        RunOutcome outcome = runtime(model).run(Stubs.ALICE, RUN, "m", SILENT);

        assertThat(outcome).isEqualTo(new RunOutcome.Done("Please approve.", List.of(intent)));
        assertThat(record().outcome()).contains("waiting_for_approval");
        assertThat(record().intentIds()).containsExactly(intent);
        assertThat(orchestrator.users).containsOnly(Stubs.ALICE.user());
    }

    // Covers: 13.1 (a drifted script is thrown on to the harness, and the record ends as failed)
    @Test
    void run_scriptDrift_isThrownAndRecordedAsFailed() {
        ScriptedModel model = script(calls(1));

        assertThatThrownBy(() -> runtime(model).run(Stubs.ALICE, RUN, "m", SILENT))
                .isInstanceOf(ScriptDriftException.class);
        assertThat(record().outcome()).contains("failed");
    }

    // Covers: 11 (D7), 23.1, I8 (trace.jsonl lines hold span names, counts, durations and codes: never the user's
    // message, the model's text, tool arguments or tool results)
    @Test
    void run_trace_holdsNoTextArgumentsOrResults() {
        RunTrace trace = new RunTrace(RUN);
        ScriptedModel model = script("""
                {"call": [{"tool": "search_catalog", "args": {"query": "SECRET-QUERY-1", "max_results": 1}},
                          {"tool": "invent_a_tool", "args": {}}]},
                {"text": "SECRET-MODEL-TEXT-2"}""");

        runtime(model).run(Stubs.ALICE, RUN, "SECRET-USER-MESSAGE-3", SILENT, trace);

        assertThat(trace.lines()).hasSize(5);
        assertThat(trace.lines()).extracting(l -> l.replaceAll(".*\"span\":\"([a-z.]+)\".*", "$1"))
                .containsExactly("llm.call", "tool.call", "tool.call", "llm.call", "agent.run");
        String all = trace.jsonl();
        assertThat(all).doesNotContain("SECRET").doesNotContain("Blue Mug").doesNotContain("invent_a_tool")
                .doesNotContain("alice").contains("\"name\":\"unknown\"").contains("\"outcome\":\"done\"");
        assertThat(all.lines()).hasSize(5);
    }

    // Covers: 23.1, I8, m5-stage4 item 1 (the waiting_for_approval check throwing after a successful propose still ends
    // the record, as failed with reason internal_error, and the exception is thrown on; its message is kept nowhere)
    @Test
    void run_statusThrowsInTheFinishCheck_recordEndsFailedInternalErrorAndRethrows() {
        IntentId intent = new IntentId("intent-1");
        AtomicInteger statusCalls = new AtomicInteger();
        // The first status call is propose_purchase's own (no quote in the stub's proposal); the next is the finish
        // path's waiting_for_approval check, and that one throws.
        Stubs.RecordingOrchestrator throwing = new Stubs.RecordingOrchestrator() {
            @Override
            public IntentStatus status(dev.ucplab.core.identity.AuthContext auth, IntentId id) {
                if (statusCalls.incrementAndGet() == 1) {
                    return super.status(auth, id);
                }
                throw new IllegalStateException("SECRET-DB-DETAIL");
            }
        };
        throwing.proposal = new ProposalResult(intent, IntentState.AWAITING_APPROVAL, Optional.empty());
        throwing.statuses = Map.of(intent, new IntentStatus(intent, IntentState.AWAITING_APPROVAL, Optional.empty(),
                Optional.empty()));
        ScriptedModel model = script("""
                {"call": [{"tool": "propose_purchase", "args": {"merchant_id": "shop-1",
                           "items": [{"item_id": "item_456", "quantity": 1}], "rationale": "r"}}]},
                {"text": "Please approve."}""");
        AgentRuntime runtime = new AgentRuntime(model, "scripted", Stubs.ports(catalog, throwing), runs,
                AgentBudget.DEFAULT, Duration.ofMinutes(5), clock);
        RunTrace trace = new RunTrace(RUN);

        assertThatThrownBy(() -> runtime.run(Stubs.ALICE, RUN, "m", SILENT, trace))
                .isInstanceOf(IllegalStateException.class).hasMessage("SECRET-DB-DETAIL");

        assertThat(record().outcome()).contains("failed");
        assertThat(record().reason()).contains("internal_error");
        assertThat(record().endedAt()).isPresent();
        assertThat(record().intentIds()).containsExactly(intent);
        assertThat(record().toolEvents()).extracting(ToolEvent::summary).containsExactly("ok");
        assertThat(statusCalls).as("the finish check ran and threw").hasValue(2);
        assertThat(record().toString()).doesNotContain("SECRET");
        assertThat(trace.jsonl()).contains("\"outcome\":\"failed\"")
                .contains("\"detail\":\"internal_error\"").doesNotContain("SECRET");
    }

    // Covers: 23.1, I8, m5-stage4 item 1 (an exception escaping the loop outside a model or tool call, here a model
    // client breaking ModelClient's contract by returning no turn, ends the record as failed, internal_error, and is
    // thrown on)
    @Test
    void run_exceptionOutsideModelAndToolCalls_recordEndsFailedInternalErrorAndRethrows() {
        ModelClient broken = (conversation, tools) -> null;
        RunTrace trace = new RunTrace(RUN);

        assertThatThrownBy(() -> runtime(broken).run(Stubs.ALICE, RUN, "m", SILENT, trace))
                .isInstanceOf(NullPointerException.class);

        assertThat(record().outcome()).contains("failed");
        assertThat(record().reason()).contains("internal_error");
        assertThat(record().endedAt()).isPresent();
        assertThat(trace.jsonl()).contains("\"outcome\":\"failed\"")
                .contains("\"detail\":\"internal_error\"");
    }

    // Covers: 23.1, m5-stage4 item 1 (the record keeps the failure reason and the budget limit; a done run has none)
    @Test
    void run_reasons_areRecorded() {
        runtime(script("{\"text\":\"hi\"}")).run(Stubs.ALICE, RUN, "m", SILENT);
        assertThat(record().reason()).isEmpty();

        RunId second = new RunId("run-2");
        runtime(script(String.join(",", java.util.Collections.nCopies(7, calls(1))))).run(Stubs.ALICE, second, "m",
                SILENT);
        assertThat(runs.find(Stubs.ALICE.user(), second).orElseThrow().reason()).contains("MODEL_CALLS");

        RunId third = new RunId("run-3");
        assertThatThrownBy(() -> runtime(script(calls(1))).run(Stubs.ALICE, third, "m", SILENT))
                .isInstanceOf(ScriptDriftException.class);
        assertThat(runs.find(Stubs.ALICE.user(), third).orElseThrow().reason()).contains("script_drift");
    }
}
