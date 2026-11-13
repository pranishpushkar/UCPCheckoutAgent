package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.report.EvidenceWriter;
import dev.ucplab.eval.report.RunResult;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M5 agent rows (m5-plan section 9, C11), each three times in one lane at time scale 4 (A-B four times, once per
 * breach): the agent proposes through the runtime with a ScriptedModel, then the approver and the recovery worker act
 * as in M4. Every run must be unaborted, expected by its row, and clean under every check.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentScenariosTest {

    private static final int RUNS = 3;
    private static final PathPattern COMPLETE = new PathPattern(Scenarios.COMPLETE);
    private static final PathPattern CHECKOUT = new PathPattern("/ucp/v1/checkout-sessions/**");

    private Lane lane;
    private RunScript script;
    private CheckContext checks;

    @BeforeAll
    void startLane(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        lane = Lane.start(0, key, dir);
        script = new RunScript(lane, TimeScale.of(4));
        checks = CheckContext.of(TimeScale.of(4), key, Scenarios.SHOP, RunScript.POLL);
    }

    @AfterAll
    void stopLane() {
        lane.close();
    }

    private RunEvidence run(Scenario s, int run) {
        return script.run(s, run, Scenarios.seedIndex(s) * 10_000L + run);
    }

    /** Runs {@code s} once: not aborted, expected by the row, every check clean, the proxy settled. */
    private RunEvidence runClean(Scenario s, int run) {
        RunEvidence e = run(s, run);
        assertThat(e.aborted()).as("%s run %d", s.id(), run).isEmpty();
        CheckResult found = Checks.run(e, checks);
        assertThat(found.all()).as("%s run %d", s.id(), run).isEmpty();
        assertThat(RunResult.of(s, e, found).expected()).as("%s run %d expected", s.id(), run).isTrue();
        assertThat(e.agentRun()).as("%s run %d agent evidence", s.id(), run).isPresent();
        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue());
        return e;
    }

    private static List<String> tools(RunEvidence e) {
        return e.agentRun().orElseThrow().record().toolEvents().stream().map(t -> t.tool()).toList();
    }

    // Covers: 13.1 (D6), S0, FR2, I1, I2, I3 (the agent searches and proposes one Blue Mug; the approver approves and
    // the worker completes: COMPLETED with one order, and the run record waits for approval)
    @Test
    void aS0_threeRuns_agentProposesThenCompletedWithOneOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S0, run);

            assertThat(e.intents()).singleElement().satisfies(p -> {
                assertThat(p.intent().state()).isEqualTo(IntentState.COMPLETED);
                assertThat(p.intent().owner()).isEqualTo(e.owner());
            });
            assertThat(e.orders()).hasSize(1);
            assertThat(tools(e)).containsExactly("search_catalog", "propose_purchase");
            assertThat(e.agentRun().get().record().outcome()).contains("waiting_for_approval");
            assertThat(e.agentRun().get().outcomeType()).isEqualTo("Done");
        }
    }

    // Covers: 13.1 (D6), S2, I1, I4, I7 (after the agent proposes, the first complete's answer is dropped; one
    // complete on the wire, reconciled, one order)
    @Test
    void aS2_threeRuns_droppedCompleteAnswerStillOneOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S2, run);

            assertThat(e.intents()).singleElement()
                    .satisfies(p -> assertThat(p.intent().state()).isEqualTo(IntentState.COMPLETED));
            assertThat(e.orders()).hasSize(1);
            List<LogEntry> completes = e.proxyLog().stream()
                    .filter(l -> l.method().equals("POST") && COMPLETE.matches(l.path())).toList();
            assertThat(completes).hasSize(1);
            assertThat(completes.getFirst().ruleId()).contains("drop-complete");
        }
    }

    // Covers: 13.1 (D6), S4, I1 (the model calls propose_purchase twice with the same basket: one intent, one order)
    @Test
    void aS4_threeRuns_proposeTwiceIsOneIntentOneOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S4, run);

            assertThat(tools(e)).containsExactly("search_catalog", "propose_purchase", "propose_purchase");
            assertThat(e.agentRun().get().record().intentIds()).hasSize(1);
            assertThat(e.intents()).singleElement()
                    .satisfies(p -> assertThat(p.intent().state()).isEqualTo(IntentState.COMPLETED));
            assertThat(e.orders()).hasSize(1);
        }
    }

    // Covers: 13.1 (D6), S11, I3, P1 (the agent proposes at a merchant missing from the registry: REJECTED, nothing
    // sent to any merchant, no order)
    @Test
    void aS11_threeRuns_unlistedMerchantRejectedByP1() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S11, run);

            assertThat(e.intents()).singleElement().satisfies(p -> {
                assertThat(p.intent().state()).isEqualTo(IntentState.REJECTED);
                assertThat(p.intent().failureReason()).hasValueSatisfying(r -> assertThat(r).isEqualTo("policy_P1"));
            });
            assertThat(e.orders()).isEmpty();
            assertThat(e.proxyLog()).noneSatisfy(l -> assertThat(CHECKOUT.matches(l.path())).isTrue());
            assertThat(e.agentRun().get().record().outcome()).contains("done");
        }
    }

    // Covers: 13.1 (D6), S12, FR3 (the agent asks before it proposes; when the first ask_user arrives the user has no
    // intent; after the answer it proposes and the purchase completes)
    @Test
    void aS12_threeRuns_askUserBeforeAnyIntentThenCompleted() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S12, run);

            RunEvidence.AgentRun agent = e.agentRun().get();
            assertThat(agent.asks()).isEqualTo(1);
            assertThat(agent.intentsAtFirstAsk()).as("no intent at first ask").contains(0);
            assertThat(tools(e)).containsExactly("ask_user", "search_catalog", "propose_purchase");
            assertThat(e.intents()).singleElement()
                    .satisfies(p -> assertThat(p.intent().state()).isEqualTo(IntentState.COMPLETED));
            assertThat(e.orders()).hasSize(1);
        }
    }

    // Covers: 6.3, section 8, S12 claim (A-B: each of the four limits breached by run index; budget_exceeded with
    // that limit, and no side effects: no intent, no approval, only discovery and catalog reads, no order)
    @Test
    void aB_fourRuns_eachBreachIsBudgetExceededWithNoSideEffects() {
        for (int run = 0; run < Scenarios.BREACHES.size(); run++) {
            RunEvidence e = runClean(Scenarios.A_B, run);

            RunEvidence.AgentRun agent = e.agentRun().get();
            assertThat(agent.outcomeType()).isEqualTo("BudgetExceeded");
            assertThat(agent.breach()).contains(Scenarios.BREACHES.get(run).name());
            assertThat(agent.record().outcome()).contains("budget_exceeded");
            assertThat(agent.record().reason()).contains(Scenarios.BREACHES.get(run).name());
            assertThat(e.intents()).isEmpty();
            assertThat(e.proposed()).isEmpty();
            assertThat(e.approvals()).isEmpty();
            assertThat(e.orders()).isEmpty();
            assertThat(e.proxyLog()).isNotEmpty().allSatisfy(l -> assertThat(RunScript.isRead(l)).isTrue());
        }
    }

    // Covers: 13.1 (a script whose tool sequence differs from expectToolSequence aborts the run after it, even though
    // every turn matched)
    @Test
    void drift_toolSequenceDiffers_abortsTheRun() {
        RunEvidence e = run(agentRow("drift-sequence.json"), 0);

        assertThat(e.aborted()).contains("agent run: the script drifted");
        assertThat(e.agentRun()).hasValueSatisfying(a -> assertThat(a.outcomeType()).isEqualTo("Done"));
        assertThat(RunResult.of(agentRow("drift-sequence.json"), e, Checks.run(e, checks)).failed()).isTrue();
    }

    // Covers: 13.1, item 1 (a turn whose when does not match throws ScriptDriftException from the run: aborted, and
    // the run record still ends as failed, script_drift)
    @Test
    void drift_whenDoesNotMatch_abortsTheRunAndTheRecordEndsFailed() {
        RunEvidence e = run(agentRow("drift-when.json"), 0);

        assertThat(e.aborted()).contains("agent run: the script drifted");
        assertThat(e.agentRun()).hasValueSatisfying(a -> {
            assertThat(a.outcomeType()).isEqualTo("aborted");
            assertThat(a.record().outcome()).contains("failed");
            assertThat(a.record().reason()).contains("script_drift");
        });
        assertThat(e.intents()).isEmpty();
    }

    // Covers: 11 (D7), 23.1, I8 (an agent row's evidence holds run.json and trace.jsonl next to intents.json, and no
    // model text, question, user message, answer, rationale or tool result anywhere in the folder)
    @Test
    void evidence_agentRow_hasRunAndTraceAndNoModelText(@TempDir Path dir) throws Exception {
        Scenario row = agentRow("drift-sequence.json");
        RunEvidence e = run(row, 1);
        Path out = EvidenceWriter.write(dir.resolve("ev"), e, Checks.run(e, checks));
        RunEvidence s12 = run(Scenarios.A_S12, 0);
        Path out12 = EvidenceWriter.write(dir.resolve("ev12"), s12, Checks.run(s12, checks));

        for (Path folder : List.of(out, out12)) {
            assertThat(folder.resolve(EvidenceWriter.RUN_FILE)).exists();
            assertThat(folder.resolve(EvidenceWriter.TRACE_FILE)).exists();
            assertThat(folder.resolve("intents.json")).exists();
            assertThat(Files.readString(folder.resolve("summary.json"))).contains("\"agent\"")
                    .contains("\"outcomeType\"");
        }
        assertThat(Files.readString(out12.resolve(EvidenceWriter.TRACE_FILE))).contains("\"span\":\"agent.run\"")
                .contains("\"name\":\"ask_user\"");
        Set<String> forbidden = Set.of("DRIFT-MODEL-TEXT", "DRIFT-RATIONALE", "Which product", "the blue mug",
                "Buy me", "I have asked", "The user chose", "untrusted");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        assertThat(files).hasSizeGreaterThanOrEqualTo(16);
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            for (String word : forbidden) {
                assertThat(text).as("%s holds %s", f.getFileName(), word).doesNotContain(word);
            }
        }
    }

    /** A copy of A-S0 driven by a test script. */
    private static Scenario agentRow(String resource) {
        return new Scenario("A-S0", "test: " + resource, "none", Set.of(IntentState.COMPLETED), List.of("I1"),
                (t, run) -> dev.ucplab.faultproxy.RuleSet.empty(), AutoApprover.Policy.APPROVE_ALL, Scenarios.SHOP,
                Scenarios.PAY_OK, AgentSpec.of("Buy me one blue mug.", resource, List.of(),
                        Set.of("waiting_for_approval"), 1, 1, false));
    }
}
