package dev.ucplab.eval;

import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.PriceTable;
import dev.ucplab.eval.report.Report;
import dev.ucplab.runtime.ModelClients;
import dev.ucplab.runtime.SystemPrompt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The evaluation's command line (13.5, m4-plan section 4). Exit codes: 0 clean; 1 any invariant violation or aborted
 * run; 2 unexpected terminal states only; 3 a harness or configuration error, or {@code --require-full} not met (fewer
 * than 200 runs per scenario, a time scale other than 1, or an unknown or dirty commit). {@code --require-full} is
 * checked before anything runs, and again on the finished report.
 *
 * <p>{@code --mode live} (C16, m5-plan section 10) runs the live rows ({@link Scenarios#live}) against the Gemini
 * model. It refuses to start, with a fixed message, without the provider on the classpath (the {@code eval-live}
 * profile) or without {@code GEMINI_API_KEY} in the environment, the only place the key is ever read from. Its caps
 * default to, and may not exceed, $5 and 2,000,000 tokens; at either, no more runs start, the report is REDUCED with the
 * reason, and the exit code is 3. At the end, the whole output folder and the captured stdout and stderr are scanned
 * for the key and the canary ({@link LeakScan}); a hit empties the folder and exits 3.
 *
 * <pre>
 * EvalMain [--mode scripted|live] [--runs N] [--time-scale K] [--lanes L] [--seed S] [--scenario ID [--run R]]
 *          [--out DIR] [--evidence failures|all] [--require-full]
 *          [--max-cost-usd USD] [--max-tokens-total N] [--prices FILE]
 * </pre>
 */
public final class EvalMain {

    static final String USAGE = "usage: EvalMain [--mode scripted|live] [--runs N] [--time-scale K] [--lanes L]"
            + " [--seed S] [--scenario ID [--run R]] [--out DIR] [--evidence failures|all] [--require-full]"
            + " [--max-cost-usd USD] [--max-tokens-total N] [--prices FILE]";

    /** The live provider, model and price file (C15). */
    static final String LIVE_PROVIDER = "gemini";
    static final String LIVE_MODEL = "gemini-3.8-flash";
    static final String PRICES = "prices/gemini-3.8-flash.json";
    static final String KEY_VARIABLE = "GEMINI_API_KEY";
    static final String NO_PROVIDER = "live mode needs the Gemini provider on the classpath: run with -Peval-live";
    static final String NO_KEY = "live mode needs GEMINI_API_KEY in the environment; it is read from nowhere else";

    private EvalMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, CommitInfo.detect(Path.of("").toAbsolutePath()),
                System::getenv, ModelClients::available));
    }

    static int run(String[] args, PrintStream out, PrintStream err, CommitInfo commit) {
        return run(args, out, err, commit, System::getenv, ModelClients::available);
    }

    /**
     * Parses {@code args}, runs the evaluation and returns the exit code. Never calls {@code System.exit}.
     *
     * @param env the environment (tests pass their own)
     * @param provider whether a model client provider of that name is on the classpath (tests pass their own)
     */
    static int run(String[] args, PrintStream out, PrintStream err, CommitInfo commit, UnaryOperator<String> env,
                   Predicate<String> provider) {
        int runs = 20;
        int k = 4;
        int lanes = 4;
        long seed = 42;
        List<String> only = new ArrayList<>();
        OptionalInt onlyRun = OptionalInt.empty();
        Path dir = Path.of("eval-harness", "target", "eval");
        Evaluation.EvidenceMode evidence = Evaluation.EvidenceMode.FAILURES;
        boolean requireFull = false;
        boolean liveMode = false;
        BigDecimal maxCost = LiveModel.MAX_COST_USD;
        long maxTokens = LiveModel.MAX_TOKENS_TOTAL;
        Optional<Path> pricesFile = Optional.empty();
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--mode" -> {
                        String mode = value(args, ++i, a);
                        liveMode = switch (mode) {
                            case "scripted" -> false;
                            case "live" -> true;
                            default -> throw new IllegalArgumentException("--mode takes scripted or live");
                        };
                    }
                    case "--runs" -> runs = Integer.parseInt(value(args, ++i, a));
                    case "--time-scale" -> k = Integer.parseInt(value(args, ++i, a));
                    case "--lanes" -> lanes = Integer.parseInt(value(args, ++i, a));
                    case "--seed" -> seed = Long.parseLong(value(args, ++i, a));
                    case "--scenario" -> only.add(value(args, ++i, a));
                    case "--run" -> onlyRun = OptionalInt.of(Integer.parseInt(value(args, ++i, a)));
                    case "--out" -> dir = Path.of(value(args, ++i, a));
                    case "--evidence" -> evidence = switch (value(args, ++i, a)) {
                        case "failures" -> Evaluation.EvidenceMode.FAILURES;
                        case "all" -> Evaluation.EvidenceMode.ALL;
                        default -> throw new IllegalArgumentException("--evidence takes failures or all");
                    };
                    case "--require-full" -> requireFull = true;
                    case "--max-cost-usd" -> maxCost = new BigDecimal(value(args, ++i, a));
                    case "--max-tokens-total" -> maxTokens = Long.parseLong(value(args, ++i, a));
                    case "--prices" -> pricesFile = Optional.of(Path.of(value(args, ++i, a)));
                    default -> throw new IllegalArgumentException("unknown argument " + a);
                }
            }
            if (onlyRun.isPresent() && only.size() != 1) {
                throw new IllegalArgumentException("--run needs exactly one --scenario");
            }
            List<Scenario> scenarios = new ArrayList<>();
            for (String id : only) {
                scenarios.add(Scenarios.byId(id).orElseThrow(() -> new IllegalArgumentException("no scenario " + id)));
            }
            if (scenarios.isEmpty()) {
                scenarios.addAll(liveMode ? Scenarios.live() : Scenarios.all());
            }
            if (liveMode && !Scenarios.live().containsAll(scenarios)) {
                throw new IllegalArgumentException("live mode runs only " + Scenarios.live().stream()
                        .map(Scenario::id).toList());
            }
            EvalConfig config = new EvalConfig(runs, TimeScale.of(k), lanes, seed);
            if (requireFull) {
                List<String> problems = new ArrayList<>();
                if (runs < Report.FULL_RUNS) {
                    problems.add(runs + " runs per scenario, fewer than " + Report.FULL_RUNS);
                }
                if (k != 1) {
                    problems.add("time scale " + k + ", not 1");
                }
                if (onlyRun.isPresent()) {
                    problems.add("--run runs a single run");
                }
                if (!commit.known()) {
                    problems.add("the commit is unknown");
                }
                if (commit.dirty()) {
                    problems.add("the working tree has uncommitted changes");
                }
                if (!problems.isEmpty()) {
                    err.println("--require-full not met: " + String.join("; ", problems));
                    return Report.HARNESS_ERROR;
                }
            }
            if (liveMode) {
                if (requireFull) {
                    throw new IllegalArgumentException("--require-full is for the scripted evaluation");
                }
                // Provider first, then the key: a -Peval-live build with no key loads the provider and refuses for
                // the key (risk 5).
                if (!provider.test(LIVE_PROVIDER)) {
                    err.println(NO_PROVIDER);
                    return Report.HARNESS_ERROR;
                }
                String key = env.apply(KEY_VARIABLE);
                if (key == null || key.isBlank()) {
                    err.println(NO_KEY);
                    return Report.HARNESS_ERROR;
                }
                PriceTable prices = pricesFile.isPresent() ? PriceTable.read(pricesFile.get()) : bundledPrices();
                return live(config, scenarios, onlyRun, dir, evidence, key, prices, maxCost, maxTokens, out, err,
                        commit);
            }
            Report report = new Evaluation(config, scenarios, onlyRun, dir, evidence).run(commit);
            out.println((report.reduced() ? report.reducedBanner() + "\n" : "") + "report: "
                    + dir.resolve("report.md") + "; exit " + report.exitCode() + " (" + report.violations()
                    + " runs with violations, " + report.unexpected() + " unexpected, " + report.aborted()
                    + " aborted)");
            if (requireFull && !report.requireFullProblems().isEmpty()) {
                err.println("--require-full not met: " + String.join("; ", report.requireFullProblems()));
                return Report.HARNESS_ERROR;
            }
            return report.exitCode();
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return Report.HARNESS_ERROR;
        } catch (IOException | RuntimeException e) {
            err.println("harness error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Report.HARNESS_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("interrupted");
            return Report.HARNESS_ERROR;
        }
    }

    /**
     * Runs a live evaluation with stdout and stderr captured, then scans the output folder and the capture for the key
     * and the canary. The clients get the key inside a {@link ModelKey}; the text read from the environment is kept
     * here only as the scan's needle, and is never printed or written.
     */
    private static int live(EvalConfig config, List<Scenario> scenarios, OptionalInt onlyRun, Path dir,
                            Evaluation.EvidenceMode evidence, String keyText, PriceTable prices, BigDecimal maxCost,
                            long maxTokens, PrintStream out, PrintStream err, CommitInfo commit)
            throws IOException, InterruptedException {
        String canary = Canary.generate();
        ModelKey key = ModelKey.of(keyText);
        LiveModel model = new LiveModel(LIVE_MODEL, SystemPrompt.VERSION, prices, maxCost, maxTokens,
                names -> ModelClients.load(LIVE_PROVIDER, key, ModelClientSettings.defaults().withModel(LIVE_MODEL)
                        .withRequestedTools(names)));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        System.setOut(new PrintStream(new Tee(stdout, captured), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(stderr, captured), true, StandardCharsets.UTF_8));
        Report report;
        boolean clean;
        try {
            report = new Evaluation(config, scenarios, onlyRun, dir, evidence, canary, model).run(commit);
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
            // Runs even when the evaluation failed: whatever it left behind is scanned.
            clean = LeakScan.enforce(dir, captured.toString(StandardCharsets.UTF_8),
                    Map.of(keyText, "the model key", canary, "the canary"), err);
        }
        if (!clean) {
            return Report.HARNESS_ERROR;
        }
        out.println((report.reduced() ? report.reducedBanner() + "\n" : "") + "report: " + dir.resolve("report.md")
                + "; exit " + report.exitCode() + " (" + report.violations() + " runs with violations, "
                + report.unexpected() + " unexpected, " + report.aborted() + " aborted); "
                + report.live().map(l -> l.tokens() + " tokens, estimated $" + l.estimatedCostUsd().toPlainString())
                        .orElse(""));
        return report.exitCode();
    }

    private static PriceTable bundledPrices() throws IOException {
        try (InputStream in = EvalMain.class.getResourceAsStream(PRICES)) {
            if (in == null) {
                throw new IllegalStateException("the bundled price file is missing");
            }
            return PriceTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Writes to the real stream and keeps a copy for the leak scan. */
    private static final class Tee extends OutputStream {
        private final OutputStream real;
        private final ByteArrayOutputStream copy;

        Tee(OutputStream real, ByteArrayOutputStream copy) {
            this.real = real;
            this.copy = copy;
        }

        @Override
        public void write(int b) throws IOException {
            real.write(b);
            synchronized (copy) {
                copy.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            real.write(b, off, len);
            synchronized (copy) {
                copy.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            real.flush();
        }
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }
}
