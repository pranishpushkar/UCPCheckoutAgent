package dev.ucplab.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.eval.EvalConfig;
import dev.ucplab.eval.Scenarios;
import dev.ucplab.eval.TimeScale;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.check.InvariantCheck;
import dev.ucplab.eval.check.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The report from fixed results (13.5, m4-plan section 11) against golden files: one clean S0 row with a violation in
 * one run, an S4 row with an aborted run, and an S11 row with an unexpected outcome; then (M5) an agent-scripted A-S0
 * row with one run's evidence, and an agent-live A-S12 row priced from the fixture price file. To regenerate the golden files
 * after a deliberate change, run with {@code -Dgolden.update=true} and review the diff.
 */
class ReportGoldenTest {

    private static final Instant AT = Instant.parse("2026-11-12T12:00:00Z");
    private static final CommitInfo COMMIT = new CommitInfo("0123456789abcdef0123456789abcdef01234567",
            "feature/m4-fault-proxy", false, "git");
    private static final List<String> RUNTIME = List.of("Java 21.0.5 (Test)", "TestOS 1.0 x86_64");

    static CheckResult checks(Map<String, String> violations) {
        Map<String, List<Violation>> m = new LinkedHashMap<>();
        for (InvariantCheck c : Checks.all()) {
            m.put(c.id(), violations.containsKey(c.id()) ? List.of(new Violation(c.id(), violations.get(c.id())))
                    : List.of());
        }
        return new CheckResult(m);
    }

    static RunResult clean(String scenario, int run, IntentState terminal, long ms) {
        return new RunResult(scenario, run, 42 + run, run % 2, Optional.empty(), Optional.of(terminal), 1, true,
                checks(Map.of()), Optional.of(Duration.ofMillis(ms)), Optional.empty(), Optional.empty());
    }

    static Report report(List<ScenarioSummary> rows) {
        return report(rows, Optional.empty());
    }

    static Report report(List<ScenarioSummary> rows, Optional<PriceTable> prices) {
        return new Report(AT, COMMIT, new EvalConfig(3, TimeScale.of(4), 2, 42), "2026-08-25", RUNTIME, rows,
                prices);
    }

    static RunResult agent(String scenario, int run, IntentState terminal, boolean live, int modelCalls, int toolCalls,
                           long in, long out, Optional<String> evidence) {
        return new RunResult(scenario, run, 42 + run, run % 2, Optional.empty(), Optional.of(terminal), 1,
                terminal == IntentState.COMPLETED, checks(Map.of()), Optional.of(Duration.ofMillis(150 + run)),
                Optional.empty(), evidence,
                Optional.of(new RunResult.AgentStats(live, modelCalls, toolCalls, in, out)));
    }

    static Report golden() {
        RunResult violated = new RunResult("S0", 2, 44, 0, Optional.empty(), Optional.of(IntentState.COMPLETED), 1,
                true, checks(Map.of("I1", "the merchant holds 2 orders for the run: [ord_1 on cs_1, ord_2 on cs_1]")),
                Optional.of(Duration.ofMillis(140)), Optional.of(Duration.ofMillis(60)),
                Optional.of("evidence/S0/run-2"));
        RunResult aborted = new RunResult("S4", 1, 20043, 1, Optional.of("no terminal state within the time limit"
                + " PT23.4375S"), Optional.empty(), 0, false,
                checks(Map.of("I1", "the run was aborted", "I2", "the run was aborted", "I3", "the run was aborted",
                        "I4", "the run was aborted", "I5", "the run was aborted", "I7", "the run was aborted")),
                Optional.empty(), Optional.empty(), Optional.of("evidence/S4/run-1"));
        RunResult unexpected = new RunResult("S11", 1, 220043, 1, Optional.empty(),
                Optional.of(IntentState.COMPLETED), 1, false, checks(Map.of()), Optional.of(Duration.ofMillis(90)),
                Optional.empty(), Optional.of("evidence/S11/run-1"));
        return report(List.of(
                new ScenarioSummary(Scenarios.S0, List.of(clean("S0", 0, IntentState.COMPLETED, 100),
                        clean("S0", 1, IntentState.COMPLETED, 120), violated)),
                new ScenarioSummary(Scenarios.S4, List.of(clean("S4", 0, IntentState.COMPLETED, 110), aborted,
                        clean("S4", 2, IntentState.COMPLETED, 130))),
                new ScenarioSummary(Scenarios.S11, List.of(clean("S11", 0, IntentState.REJECTED, 20), unexpected,
                        clean("S11", 2, IntentState.REJECTED, 25))),
                new ScenarioSummary(Scenarios.A_S0, List.of(
                        agent("A-S0", 0, IntentState.COMPLETED, false, 3, 2, 0, 0, Optional.empty()),
                        agent("A-S0", 1, IntentState.COMPLETED, false, 3, 2, 0, 0, Optional.of("evidence/A-S0/run-1")),
                        agent("A-S0", 2, IntentState.COMPLETED, false, 3, 3, 0, 0, Optional.empty()))),
                new ScenarioSummary(Scenarios.A_S12, List.of(
                        agent("A-S12", 0, IntentState.COMPLETED, true, 4, 3, 9_000, 400, Optional.empty()),
                        agent("A-S12", 1, IntentState.COMPLETED, true, 5, 4, 12_000, 600, Optional.empty()),
                        agent("A-S12", 2, IntentState.REJECTED, true, 4, 3, 10_000, 500, Optional.empty())))),
                Optional.of(prices()));
    }

    static PriceTable prices() {
        try {
            return PriceTable.read(PriceTableTest.fixture());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertGolden(String name, String actual) throws IOException {
        if (Boolean.getBoolean("golden.update")) {
            Path file = Path.of("src/test/resources/golden", name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        try (InputStream in = ReportGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("golden file " + name).isNotNull();
            assertThat(actual).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // Covers: 13.5 (report.json from fixed results matches the golden file)
    @Test
    void reportJson_fixedResults_matchesGolden() throws IOException {
        assertGolden("report.json", ReportJson.write(golden()));
    }

    // Covers: 13.5 (report.md from fixed results matches the golden file; a reduced report says so first)
    @Test
    void reportMarkdown_fixedResults_matchesGolden() throws IOException {
        String md = ReportMarkdown.write(golden());

        assertThat(md.lines().findFirst()).contains(
                "**REDUCED: 3 runs per scenario, time scale 4 — not an acceptance report**");
        assertGolden("report.md", md);
    }

    // Covers: 13.3, 13.4, m5-plan section 9 (every row has a mode; agent rows fill model and tool calls p50/p95; tokens
    // and cost per completed purchase only for live rows, priced from the file; trace links where evidence exists;
    // orchestrator-direct and scripted rows say why a column is empty)
    @Test
    void report_agentAndDirectRows_fillTheM5Columns() {
        Report r = golden();
        String md = ReportMarkdown.write(r);
        String json = ReportJson.write(r);

        assertThat(r.modes()).containsExactly(Mode.ORCHESTRATOR_DIRECT, Mode.AGENT_SCRIPTED, Mode.AGENT_LIVE);
        assertThat(md).contains("- **Mode:** scripted; rows: orchestrator-direct, agent-scripted, agent-live");
        assertThat(md.lines().filter(l -> l.startsWith("| S0 |")).findFirst().orElseThrow())
                .contains("| orchestrator-direct |").contains("n/a (orchestrator-direct) | n/a (orchestrator-direct)"
                        + " | n/a (orchestrator-direct) | n/a (orchestrator-direct) | n/a (orchestrator-direct) |");
        assertThat(md.lines().filter(l -> l.startsWith("| A-S0 |")).findFirst().orElseThrow())
                .contains("| agent-scripted |").contains("| 3/3 | 2/3 | n/a (scripted model) | n/a (scripted model)"
                        + " | [evidence/A-S0/run-1/trace.jsonl](evidence/A-S0/run-1/trace.jsonl) |");
        // Live: tokens 9400, 12600, 10500; cost (fixture: 1 and 10 per million) 0.013 + 0.018 + 0.015 over 2
        // completed purchases.
        assertThat(md.lines().filter(l -> l.startsWith("| A-S12 |")).findFirst().orElseThrow())
                .contains("| agent-live |").contains("| 4/5 | 3/4 | 10500/12600 | 0.023 USD | n/a (evidence for failed"
                        + " runs only) |");
        assertThat(json).contains("\"schema\": \"ucplab.eval.report/2\"").contains("\"mode\": \"agent-live\"")
                .contains("\"amount\": \"0.023\"").contains("\"model\": \"fixture-model-not-real\"")
                .doesNotContain("\"driver\"").doesNotContain("\"deferred\"");
    }

    // Covers: 13.3 (a live row without a price file, or with no completed purchase, says so rather than guessing)
    @Test
    void report_liveRowWithoutPricesOrCompletions_saysWhy() {
        ScenarioSummary live = new ScenarioSummary(Scenarios.A_S12, List.of(
                agent("A-S12", 0, IntentState.REJECTED, true, 4, 3, 9_000, 400, Optional.empty())));

        assertThat(ReportMarkdown.write(report(List.of(live)))).contains("| n/a (no price file) |");
        assertThat(ReportMarkdown.write(report(List.of(live), Optional.of(prices()))))
                .contains("| n/a (no completed purchase) |");
    }

    // Covers: 13.5 (exit 0: no violation, no abort, every outcome expected)
    @Test
    void exitCode_cleanReport_isZero() {
        Report r = report(List.of(new ScenarioSummary(Scenarios.S0, List.of(clean("S0", 0, IntentState.COMPLETED,
                100)))));

        assertThat(r.exitCode()).isEqualTo(Report.CLEAN);
    }

    // Covers: 13.5 (exit 1: any invariant violation, even with every outcome expected)
    @Test
    void exitCode_violation_isOne() {
        RunResult bad = new RunResult("S0", 0, 42, 0, Optional.empty(), Optional.of(IntentState.COMPLETED), 1, true,
                checks(Map.of("I7", "key k was sent with 2 different bodies")), Optional.of(Duration.ofMillis(9)),
                Optional.empty(), Optional.empty());

        assertThat(report(List.of(new ScenarioSummary(Scenarios.S0, List.of(bad)))).exitCode())
                .isEqualTo(Report.VIOLATION_OR_ABORT);
    }

    // Covers: 13.5 (exit 1: an aborted run, even with no violation recorded)
    @Test
    void exitCode_abortedRun_isOne() {
        RunResult aborted = new RunResult("S0", 0, 42, 0, Optional.of("interrupted"), Optional.empty(), 0, false,
                checks(Map.of()), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(report(List.of(new ScenarioSummary(Scenarios.S0, List.of(aborted)))).exitCode())
                .isEqualTo(Report.VIOLATION_OR_ABORT);
    }

    // Covers: 13.5 (exit 2: unexpected terminal states only)
    @Test
    void exitCode_unexpectedOutcomeOnly_isTwo() {
        RunResult odd = new RunResult("S11", 0, 42, 0, Optional.empty(), Optional.of(IntentState.COMPLETED), 1, false,
                checks(Map.of()), Optional.of(Duration.ofMillis(9)), Optional.empty(), Optional.empty());

        assertThat(report(List.of(new ScenarioSummary(Scenarios.S11, List.of(odd)))).exitCode())
                .isEqualTo(Report.UNEXPECTED_OUTCOME);
    }

    // Covers: 13.5 (--require-full names every gap: runs, time scale, unknown or dirty commit)
    @Test
    void requireFull_reducedReport_namesEveryGap() {
        Report r = new Report(AT, new CommitInfo(CommitInfo.UNKNOWN, CommitInfo.UNKNOWN, true, "none"),
                new EvalConfig(20, TimeScale.of(4), 1, 42), "2026-08-25", RUNTIME,
                List.of(new ScenarioSummary(Scenarios.S0, List.of(clean("S0", 0, IntentState.COMPLETED, 9)))));

        assertThat(r.requireFullProblems()).containsExactly("time scale 4, not 1",
                "S0 has 1 runs, fewer than 200", "the commit is unknown", "the working tree has uncommitted changes");
        assertThat(r.reduced()).isTrue();
    }

    // Covers: 13.5, m4-plan section 4 (a reduced report whose rows ran different numbers of times names the rows with
    // fewer runs in its first line and in its configuration)
    @Test
    void reducedBanner_rowsWithFewerRuns_namesThem() {
        List<RunResult> three = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            three.add(clean("S0", run, IntentState.COMPLETED, 9));
        }
        Report r = new Report(AT, COMMIT, new EvalConfig(3, TimeScale.of(4), 2, 42, Map.of("S9", 1)), "2026-08-25",
                RUNTIME, List.of(new ScenarioSummary(Scenarios.S0, three),
                        new ScenarioSummary(Scenarios.S9, List.of(clean("S9", 0, IntentState.COMPLETED, 9)))));

        assertThat(r.reducedBanner()).isEqualTo("REDUCED: 3 runs per scenario (S9 1), time scale 4 — not an acceptance"
                + " report");
        assertThat(ReportMarkdown.write(r).lines().findFirst()).hasValueSatisfying(
                l -> assertThat(l).contains(r.reducedBanner()));
        assertThat(ReportMarkdown.write(r)).contains("3 (fewer: S9 1)");
        assertThat(ReportJson.write(r)).contains("\"fewerRuns\": {").contains("\"S9\": 1");
    }
}
