package dev.ucplab.eval;

import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.EvidenceWriter;
import dev.ucplab.eval.report.Report;
import dev.ucplab.eval.report.ReportJson;
import dev.ucplab.eval.report.ReportMarkdown;
import dev.ucplab.eval.report.RunResult;
import dev.ucplab.eval.report.ScenarioSummary;
import dev.ucplab.merchant.LabMerchant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One evaluation (13.1, m4-plan section 4): starts the lanes, runs every (scenario, run) once across them, checks each
 * run, writes the evidence of the runs that failed (or of every run), and writes {@code report.json} and
 * {@code report.md} under the output folder.
 *
 * <p>The I8 canary ({@link Canary}) is generated here once and handed to every lane's runs (section 11).
 *
 * <p>The {@code bodyHmac} key is generated here, held in memory only, and handed to every lane's proxy and to the
 * checks. The lanes' database files hold the agent's frozen bodies (and so the mock card tokens); they live in a
 * temporary folder outside the output folder and are deleted at the end. So do the agent child processes' log files;
 * their text goes into the evidence of the runs that keep one.
 */
public final class Evaluation {

    /** Which runs get an evidence folder. */
    public enum EvidenceMode {
        /** Only runs that were aborted, broke an invariant or ended unexpectedly. */
        FAILURES,
        /** Every run. */
        ALL
    }

    private final EvalConfig config;
    private final List<Scenario> scenarios;
    private final OptionalInt onlyRun;
    private final Path out;
    private final EvidenceMode evidence;
    private final Consumer<String> tokenSeen;
    private final Consumer<RunEvidence> evidenceSeen;
    private final byte[] hmacKey;
    private final String canary;

    public Evaluation(EvalConfig config, List<Scenario> scenarios, OptionalInt onlyRun, Path out,
                      EvidenceMode evidence) {
        this(config, scenarios, onlyRun, out, evidence, t -> { }, e -> { }, randomKey(), Canary.generate());
    }

    /**
     * The redaction test chooses the key and sees every approval token and every run's raw evidence, to search the
     * output for all of them.
     */
    Evaluation(EvalConfig config, List<Scenario> scenarios, OptionalInt onlyRun, Path out, EvidenceMode evidence,
               Consumer<String> tokenSeen, Consumer<RunEvidence> evidenceSeen, byte[] hmacKey) {
        this(config, scenarios, onlyRun, out, evidence, tokenSeen, evidenceSeen, hmacKey, Canary.generate());
    }

    /** @param canary the evaluation's I8 canary (section 11); the redaction test chooses it to search for it */
    Evaluation(EvalConfig config, List<Scenario> scenarios, OptionalInt onlyRun, Path out, EvidenceMode evidence,
               Consumer<String> tokenSeen, Consumer<RunEvidence> evidenceSeen, byte[] hmacKey, String canary) {
        this.config = Objects.requireNonNull(config, "config");
        this.scenarios = List.copyOf(scenarios);
        if (this.scenarios.isEmpty()) {
            throw new IllegalArgumentException("no scenarios to run");
        }
        this.onlyRun = Objects.requireNonNull(onlyRun, "onlyRun");
        onlyRun.ifPresent(r -> {
            if (r < 0 || r >= config.runsPerScenario()) {
                throw new IllegalArgumentException("run " + r + " is outside 0.." + (config.runsPerScenario() - 1));
            }
        });
        this.out = Objects.requireNonNull(out, "out");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.tokenSeen = Objects.requireNonNull(tokenSeen, "tokenSeen");
        this.evidenceSeen = Objects.requireNonNull(evidenceSeen, "evidenceSeen");
        if (hmacKey.length != 32) {
            throw new IllegalArgumentException("the bodyHmac key must be 32 bytes: " + hmacKey.length);
        }
        this.hmacKey = hmacKey.clone();
        this.canary = Objects.requireNonNull(canary, "canary");
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private record Task(Scenario scenario, int run, long seed) {
    }

    /** Runs the evaluation and writes the report; returns it. */
    public Report run(CommitInfo commit) throws IOException, InterruptedException {
        Files.createDirectories(out);
        Queue<Task> tasks = new ConcurrentLinkedQueue<>();
        for (Scenario s : scenarios) {
            int index = Scenarios.seedIndex(s);
            if (onlyRun.isPresent()) {
                tasks.add(new Task(s, onlyRun.getAsInt(), config.seed(index, onlyRun.getAsInt())));
            } else {
                for (int r = 0; r < config.runsOf(s.id()); r++) {
                    tasks.add(new Task(s, r, config.seed(index, r)));
                }
            }
        }
        byte[] key = hmacKey.clone();
        int laneCount = Math.min(config.lanes(), tasks.size());
        Path work = Files.createTempDirectory("ucplab-eval-");
        Queue<RunResult> results = new ConcurrentLinkedQueue<>();
        try {
            List<Lane> lanes = new ArrayList<>();
            try {
                for (int i = 0; i < laneCount; i++) {
                    lanes.add(Lane.start(i, key, work));
                }
                CheckContext checks = CheckContext.of(config.timeScale(), key, Scenarios.SHOP, RunScript.POLL);
                Semaphore childSlots = new Semaphore(ChildProcess.MAX_CHILDREN);
                ExecutorService pool = Executors.newFixedThreadPool(laneCount, r -> {
                    Thread t = new Thread(r, "eval-lane");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    List<Future<?>> running = new ArrayList<>();
                    for (Lane lane : lanes) {
                        RunScript script = new RunScript(lane, config.timeScale(), tokenSeen, childSlots, canary);
                        running.add(pool.submit(() -> drain(tasks, script, checks, results)));
                    }
                    for (Future<?> f : running) {
                        try {
                            f.get();
                        } catch (ExecutionException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof UncheckedIOException u) {
                                throw u.getCause();
                            }
                            throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            } finally {
                lanes.forEach(Lane::close);
            }
        } finally {
            deleteTree(work);
        }
        List<ScenarioSummary> rows = new ArrayList<>();
        for (Scenario s : scenarios) {
            rows.add(new ScenarioSummary(s, results.stream().filter(r -> r.scenarioId().equals(s.id())).toList()));
        }
        Report report = new Report(Instant.now(), commit, config, LabMerchant.UCP_VERSION, runtime(), rows);
        Files.writeString(out.resolve("report.json"), ReportJson.write(report), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.md"), ReportMarkdown.write(report), StandardCharsets.UTF_8);
        return report;
    }

    /** One lane's loop: take the next task until none are left. */
    private void drain(Queue<Task> tasks, RunScript script, CheckContext checks, Queue<RunResult> results) {
        for (Task t = tasks.poll(); t != null; t = tasks.poll()) {
            RunEvidence e = script.run(t.scenario(), t.run(), t.seed());
            evidenceSeen.accept(e);
            // Each kill of the agent process extends the 7.6 bounds of I5 by one takeover.
            CheckResult found = Checks.run(e, checks.withCrashes(e.crashes()));
            RunResult result = RunResult.of(t.scenario(), e, found);
            if (evidence == EvidenceMode.ALL || result.failed()) {
                String rel = "evidence/" + t.scenario().id() + "/run-" + t.run();
                try {
                    EvidenceWriter.write(out.resolve(rel), e, found);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
                result = result.withEvidence(rel);
            }
            results.add(result);
        }
    }

    static List<String> runtime() {
        return List.of("Java " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")",
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                        + System.getProperty("os.arch"));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
