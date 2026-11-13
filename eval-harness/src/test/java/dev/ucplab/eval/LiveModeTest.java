package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.PriceTable;
import dev.ucplab.eval.report.Report;
import dev.ucplab.eval.report.ScenarioSummary;
import dev.ucplab.runtime.ScriptedModel;
import dev.ucplab.runtime.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C16, offline: live mode's wiring with a stub client in place of the live model. The stub replays A-S12's script and
 * reports a fixed usage per turn, so a fake price table makes every run cost a known amount.
 */
class LiveModeTest {

    private static final CommitInfo COMMIT = new CommitInfo("0123456789abcdef0123456789abcdef01234567", "test", false,
            "git");
    private static final EvalConfig ONE_LANE = new EvalConfig(10, TimeScale.of(4), 1, 7);

    /**
     * A-S12's script as a live client: names its request's tools to the observer, reports {@code usage}, and names
     * {@code answeredBy} as the model that answered each call.
     */
    private static final class StubClient implements ModelClient {
        private final ScriptedModel script = ScriptedModel.fromResource(Scenarios.class, "scripts/a-s12-ask-first.json");
        private final Consumer<List<String>> requested;
        private final Consumer<String> answering;
        private final String answeredBy;
        private final Usage usage;
        private final List<String> offered;

        StubClient(Consumer<List<String>> requested, Consumer<String> answering, String answeredBy, Usage usage,
                   List<String> offered) {
            this.requested = requested;
            this.answering = answering;
            this.answeredBy = answeredBy;
            this.usage = usage;
            this.offered = offered;
        }

        StubClient(Consumer<List<String>> requested, Consumer<String> answering, Usage usage, List<String> offered) {
            this(requested, answering, "stub-model", usage, offered);
        }

        @Override
        public ModelTurn generate(Conversation conversation, List<ToolSpec> tools) {
            requested.accept(offered.isEmpty() ? tools.stream().map(ToolSpec::name).toList() : offered);
            ModelTurn t = script.generate(conversation, tools);
            answering.accept(answeredBy);
            return new ModelTurn(t.text(), t.toolCalls(), usage);
        }
    }

    /** {@code perMillion} USD per million input tokens, nothing for output. */
    private static PriceTable prices(String perMillion) {
        return new PriceTable("stub-model", LocalDate.of(2026, 11, 13), "USD", new BigDecimal(perMillion),
                BigDecimal.ZERO, "test");
    }

    private static LiveModel live(PriceTable prices, String maxCost, long maxTokens,
                                  BiFunction<Consumer<List<String>>, Consumer<String>, ModelClient> clients) {
        return new LiveModel("stub-model", "v1", prices, new BigDecimal(maxCost), maxTokens, clients);
    }

    private static Report run(Path out, LiveModel live) throws Exception {
        return new Evaluation(ONE_LANE, List.of(Scenarios.A_S12), OptionalInt.empty(), out,
                Evaluation.EvidenceMode.FAILURES, Canary.generate(), live).run(COMMIT);
    }

    // Covers: R4, 13.1 (m5-plan risk 4: at the cost cap no more runs start, and the report is REDUCED with the reason
    // and exits non-zero)
    @Test
    void costCap_stopsNewRuns_reducedWithTheReason(@TempDir Path out) throws Exception {
        // 4 model turns of 1,000 input tokens at $100 per million: $0.40 a run, so the $1 cap is reached after 3 runs.
        Report report = run(out, live(prices("100"), "1", LiveModel.MAX_TOKENS_TOTAL,
                (names, answering) -> new StubClient(names, answering, new Usage(1_000, 0), List.of())));

        ScenarioSummary row = report.scenarios().getFirst();
        assertThat(row.n()).isEqualTo(3);
        assertThat(row.aborted()).isZero();
        assertThat(report.stopped()).hasValueSatisfying(s -> assertThat(s).startsWith("live cost cap reached"));
        assertThat(report.reduced()).isTrue();
        assertThat(report.reducedBanner()).startsWith("REDUCED: live cost cap reached: estimated $1.2 of"
                + " --max-cost-usd 1; ");
        assertThat(report.exitCode()).isEqualTo(Report.HARNESS_ERROR);
        assertThat(report.live()).hasValueSatisfying(l -> {
            assertThat(l.tokens()).isEqualTo(12_000);
            assertThat(l.estimatedCostUsd()).isEqualByComparingTo("1.2");
        });
        String md = Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8);
        assertThat(md).startsWith("**REDUCED: live cost cap reached").contains("# Live evaluation report")
                .contains("- **Mode:** live").contains("- **Stopped early:** live cost cap reached")
                .contains("| A-S12 | agent-live |");
        assertThat(Files.readString(out.resolve("report.json"), StandardCharsets.UTF_8))
                .contains("\"mode\": \"live\"").contains("\"estimatedCostUsd\": \"1.2\"");
    }

    // Covers: R4 (at the token cap no more runs start)
    @Test
    void tokenCap_stopsNewRuns(@TempDir Path out) throws Exception {
        Report report = run(out, live(prices("0"), "5", 10_000,
                (names, answering) -> new StubClient(names, answering, new Usage(1_000, 0), List.of())));

        assertThat(report.scenarios().getFirst().n()).isEqualTo(3);
        assertThat(report.stopped()).hasValue("live token cap reached: 12000 of --max-tokens-total 10000");
        assertThat(report.exitCode()).isEqualTo(Report.HARNESS_ERROR);
    }

    // Covers: R4 (under both caps every run runs, and the report is not stopped)
    @Test
    void underTheCaps_everyRunRuns(@TempDir Path out) throws Exception {
        Report report = run(out, live(prices("1"), "5", LiveModel.MAX_TOKENS_TOTAL,
                (names, answering) -> new StubClient(names, answering, new Usage(100, 10), List.of())));

        assertThat(report.scenarios().getFirst().n()).isEqualTo(10);
        assertThat(report.stopped()).isEmpty();
        assertThat(report.exitCode()).isEqualTo(Report.CLEAN);
    }

    // Covers: S10, S12, R4 (a live run whose record ends failed, here because the model never answered, is an aborted
    // run, never a pass)
    @Test
    void failedLiveRun_isAborted(@TempDir Path out) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Report report = run(out, live(prices("1"), "5", LiveModel.MAX_TOKENS_TOTAL, (names, answering) -> (c, tools) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("the model is down");
        }));

        ScenarioSummary row = report.scenarios().getFirst();
        assertThat(calls.get()).isEqualTo(10);
        assertThat(row.aborted()).isEqualTo(10);
        assertThat(report.exitCode()).isEqualTo(Report.VIOLATION_OR_ABORT);
        assertThat(Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8))
                .contains("live agent run failed: model_error");
    }

    // Covers: R4 (the report lists each live row's answering model versions, and flags any that differ from the model
    // requested: an id can be routed to another model)
    @Test
    void answeringModel_listedPerRow_differenceFlagged(@TempDir Path same, @TempDir Path routed) throws Exception {
        Report asSent = run(same, live(prices("1"), "5", LiveModel.MAX_TOKENS_TOTAL,
                (names, answering) -> new StubClient(names, answering, new Usage(100, 10), List.of())));
        Report other = run(routed, live(prices("1"), "5", LiveModel.MAX_TOKENS_TOTAL,
                (names, answering) -> new StubClient(names, answering, "stub-model-routed", new Usage(100, 10),
                        List.of())));

        assertThat(asSent.scenarios().getFirst().answeringModels()).containsExactly(Map.entry("stub-model", 10));
        assertThat(Files.readString(same.resolve("report.md"), StandardCharsets.UTF_8))
                .contains("## Answering model").contains("| A-S12 | stub-model | stub-model (10 runs) | no |");
        assertThat(Files.readString(routed.resolve("report.md"), StandardCharsets.UTF_8))
                .contains("| A-S12 | stub-model | stub-model-routed (10 runs) | **yes** |");
        assertThat(Files.readString(routed.resolve("report.json"), StandardCharsets.UTF_8))
                .contains("\"answeringModels\": {").contains("\"stub-model-routed\": 10")
                .contains("\"answeringModelDiffers\": true");
        assertThat(other.exitCode()).isEqualTo(Report.CLEAN);
    }

    // Covers: I6 (the live check reads the request the client built: one offering other tools aborts the run)
    @Test
    void liveI6_requestWithOtherTools_aborts(@TempDir Path out) throws Exception {
        List<String> wrong = List.of("search_catalog", "get_product", "propose_purchase", "get_purchase_status",
                "ask_user", "complete_checkout");
        Report report = run(out, live(prices("1"), "5", LiveModel.MAX_TOKENS_TOTAL,
                (names, answering) -> new StubClient(names, answering, new Usage(100, 10), wrong)));

        assertThat(wrong).isNotEqualTo(ToolRegistry.NAMES);
        assertThat(report.scenarios().getFirst().aborted()).isEqualTo(10);
        assertThat(Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8))
                .contains("request(s) the live client built offered tools other than");
    }

    private static int main(UnaryOperator<String> env, boolean provider, ByteArrayOutputStream err, String... args) {
        return EvalMain.run(args, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), COMMIT, env, name -> provider);
    }

    private static final String FAKE_KEY = "AIzaFAKE-not-a-real-key-0123456789abcdef";

    /** EvalMain in live mode with a stub in place of the Gemini provider, counting the clients it builds. */
    private static int mainWithStub(AtomicInteger clients, AtomicReference<ModelClientSettings> seen,
                                    ByteArrayOutputStream err, String... args) {
        Map<String, String> withKey = Map.of(EvalMain.KEY_VARIABLE, FAKE_KEY);
        return EvalMain.run(args, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), COMMIT, withKey::get, name -> true,
                (key, settings) -> {
                    clients.incrementAndGet();
                    seen.set(settings);
                    return new StubClient(settings.requestedTools(), settings.answeringModel(),
                            settings.model().orElseThrow(), new Usage(100, 10), List.of());
                });
    }

    // Covers: R4 (--model selects its price file and goes to the client's settings, and the report names it)
    @Test
    void evalMain_model_selectsItsPriceFile_reportNamesIt(@TempDir Path out) throws Exception {
        AtomicInteger clients = new AtomicInteger();
        AtomicReference<ModelClientSettings> seen = new AtomicReference<>();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = mainWithStub(clients, seen, err, "--mode", "live", "--model", "gemini-3.6-flash", "--scenario",
                "A-S12", "--runs", "1", "--lanes", "1", "--evidence", "all", "--out", out.toString());

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(Report.CLEAN);
        assertThat(clients.get()).isEqualTo(1);
        assertThat(seen.get().model()).hasValue("gemini-3.6-flash");
        String md = Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8);
        assertThat(md).contains("- **Model:** gemini-3.6-flash")
                .contains("- **Prices:** gemini-3.6-flash, 0.75 / 3.75 USD per million input / output tokens, as of"
                        + " 2026-11-13 (https://aistudio.google.com/docs/pricing)")
                .contains("| A-S12 | gemini-3.6-flash | gemini-3.6-flash (1 run) | no |");
        assertThat(Files.readString(out.resolve("report.json"), StandardCharsets.UTF_8))
                .contains("\"model\": \"gemini-3.6-flash\"");
        // Each run's evidence keeps the versions that answered it, as ids.
        try (var files = Files.walk(out)) {
            Path summary = files.filter(f -> f.getFileName().toString().equals("summary.json")).findFirst()
                    .orElseThrow();
            assertThat(Files.readString(summary, StandardCharsets.UTF_8)).contains("\"answeringModels\": [")
                    .contains("\"gemini-3.6-flash\"");
        }
    }

    // Covers: R4 (without --model, live mode keeps the pinned model and its price file)
    @Test
    void evalMain_noModel_pinnedModel(@TempDir Path out) throws Exception {
        AtomicInteger clients = new AtomicInteger();
        AtomicReference<ModelClientSettings> seen = new AtomicReference<>();

        int exit = mainWithStub(clients, seen, new ByteArrayOutputStream(), "--mode", "live", "--scenario", "A-S12",
                "--runs", "1", "--lanes", "1", "--out", out.toString());

        assertThat(exit).isEqualTo(Report.CLEAN);
        assertThat(seen.get().model()).hasValue(EvalMain.LIVE_MODEL);
        assertThat(EvalMain.LIVE_MODEL).isEqualTo("gemini-3.8-flash");
        assertThat(Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8))
                .contains("- **Prices:** gemini-3.8-flash, 0.75 / 3.75 USD");
    }

    // Covers: R4 (a model with no price file, or a price file for another model, is refused before any client is
    // built, with a fixed message)
    @Test
    void evalMain_modelWithoutItsPriceFile_refusedBeforeAnyCall(@TempDir Path dir) throws Exception {
        AtomicInteger clients = new AtomicInteger();
        AtomicReference<ModelClientSettings> seen = new AtomicReference<>();
        ByteArrayOutputStream unknown = new ByteArrayOutputStream();
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        Path prices38 = dir.resolve("prices.json");
        Files.writeString(prices38, "{\"model\":\"gemini-3.8-flash\",\"date\":\"2026-11-13\",\"currency\":\"USD\","
                + "\"inputPerMillionTokens\":\"0.75\",\"outputPerMillionTokens\":\"3.75\",\"source\":\"test\"}");

        assertThat(mainWithStub(clients, seen, unknown, "--mode", "live", "--model", "gemini-9-flash", "--out",
                dir.resolve("a").toString())).isEqualTo(Report.HARNESS_ERROR);
        assertThat(mainWithStub(clients, seen, other, "--mode", "live", "--model", "gemini-3.6-flash", "--prices",
                prices38.toString(), "--out", dir.resolve("b").toString())).isEqualTo(Report.HARNESS_ERROR);

        assertThat(clients.get()).isZero();
        assertThat(unknown.toString(StandardCharsets.UTF_8)).contains("no price file for model gemini-9-flash:"
                + " live mode needs prices/gemini-9-flash.json").doesNotContain(FAKE_KEY);
        assertThat(other.toString(StandardCharsets.UTF_8))
                .contains("the price file names gemini-3.8-flash, not gemini-3.6-flash");
    }

    // Covers: R4 (a model id outside [a-z0-9.-]{1,64} is refused, so it cannot leave the prices folder)
    @Test
    void evalMain_badModelId_refused() {
        AtomicInteger clients = new AtomicInteger();
        AtomicReference<ModelClientSettings> seen = new AtomicReference<>();
        for (String id : List.of("../gemini-3.8-flash", "prices/x", "Gemini-3.6-Flash", "", "a".repeat(65),
                "gemini 3.6")) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertThat(mainWithStub(clients, seen, err, "--mode", "live", "--model", id))
                    .as(id).isEqualTo(Report.HARNESS_ERROR);
            assertThat(err.toString(StandardCharsets.UTF_8)).as(id)
                    .contains("--model takes a model id of a-z, 0-9, '.' and '-', 1 to 64 characters")
                    .contains(EvalMain.USAGE);
        }
        assertThat(clients.get()).isZero();
    }

    // Covers: R4 (--model is for live mode; with the scripted evaluation it is a usage error)
    @Test
    void evalMain_modelInScriptedMode_usageError() {
        ByteArrayOutputStream defaultMode = new ByteArrayOutputStream();
        ByteArrayOutputStream scripted = new ByteArrayOutputStream();

        assertThat(main(name -> null, false, defaultMode, "--model", "gemini-3.6-flash", "--scenario", "S1"))
                .isEqualTo(Report.HARNESS_ERROR);
        assertThat(main(name -> null, false, scripted, "--mode", "scripted", "--model", "gemini-3.6-flash"))
                .isEqualTo(Report.HARNESS_ERROR);

        assertThat(defaultMode.toString(StandardCharsets.UTF_8)).contains("--model is for --mode live")
                .contains(EvalMain.USAGE);
        assertThat(scripted.toString(StandardCharsets.UTF_8)).contains("--model is for --mode live");
    }

    // Covers: R4, 15 (m5-plan risk 5: live mode refuses without the provider, then without the key, with fixed
    // messages; a fake key never shows in what it prints)
    @Test
    void evalMain_live_refusesWithoutProviderOrKey() {
        String fakeKey = "AIzaFAKE-not-a-real-key-0123456789abcdef";
        Map<String, String> withKey = Map.of(EvalMain.KEY_VARIABLE, fakeKey);
        ByteArrayOutputStream noProvider = new ByteArrayOutputStream();
        ByteArrayOutputStream noKey = new ByteArrayOutputStream();
        ByteArrayOutputStream blankKey = new ByteArrayOutputStream();

        assertThat(main(withKey::get, false, noProvider, "--mode", "live")).isEqualTo(Report.HARNESS_ERROR);
        assertThat(main(name -> null, true, noKey, "--mode", "live")).isEqualTo(Report.HARNESS_ERROR);
        assertThat(main(name -> "  ", true, blankKey, "--mode", "live")).isEqualTo(Report.HARNESS_ERROR);

        assertThat(noProvider.toString(StandardCharsets.UTF_8)).contains(EvalMain.NO_PROVIDER).doesNotContain(fakeKey);
        assertThat(noKey.toString(StandardCharsets.UTF_8)).contains(EvalMain.NO_KEY);
        assertThat(blankKey.toString(StandardCharsets.UTF_8)).contains(EvalMain.NO_KEY);
    }

    // Covers: R4 (the caps may only be lowered; live mode runs only S10 and S12; a fake key never shows)
    @Test
    void evalMain_live_refusesHigherCapsAndOtherRows() {
        String fakeKey = "AIzaFAKE-not-a-real-key-0123456789abcdef";
        Map<String, String> withKey = Map.of(EvalMain.KEY_VARIABLE, fakeKey);
        ByteArrayOutputStream cost = new ByteArrayOutputStream();
        ByteArrayOutputStream tokens = new ByteArrayOutputStream();
        ByteArrayOutputStream row = new ByteArrayOutputStream();

        assertThat(main(withKey::get, true, cost, "--mode", "live", "--max-cost-usd", "6"))
                .isEqualTo(Report.HARNESS_ERROR);
        assertThat(main(withKey::get, true, tokens, "--mode", "live", "--max-tokens-total", "2000001"))
                .isEqualTo(Report.HARNESS_ERROR);
        assertThat(main(withKey::get, true, row, "--mode", "live", "--scenario", "A-S0"))
                .isEqualTo(Report.HARNESS_ERROR);

        assertThat(cost.toString(StandardCharsets.UTF_8)).contains("--max-cost-usd must be above 0 and at most 5")
                .doesNotContain(fakeKey);
        assertThat(tokens.toString(StandardCharsets.UTF_8)).contains("--max-tokens-total must be 1 to 2000000");
        assertThat(row.toString(StandardCharsets.UTF_8)).contains("live mode runs only [A-S10, A-S12]");
    }
}
