package dev.ucplab.eval;

import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.Report;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * The evaluation's command line (13.5, m4-plan section 4). Exit codes: 0 clean; 1 any invariant violation or aborted
 * run; 2 unexpected terminal states only; 3 a harness or configuration error, or {@code --require-full} not met (fewer
 * than 200 runs per scenario, a time scale other than 1, or an unknown or dirty commit). {@code --require-full} is
 * checked before anything runs, and again on the finished report.
 *
 * <pre>
 * EvalMain [--mode scripted] [--runs N] [--time-scale K] [--lanes L] [--seed S] [--scenario ID [--run R]]
 *          [--out DIR] [--evidence failures|all] [--require-full]
 * </pre>
 */
public final class EvalMain {

    static final String USAGE = "usage: EvalMain [--mode scripted] [--runs N] [--time-scale K] [--lanes L] [--seed S]"
            + " [--scenario ID [--run R]] [--out DIR] [--evidence failures|all] [--require-full]";

    private EvalMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, CommitInfo.detect(Path.of("").toAbsolutePath())));
    }

    /** Parses {@code args}, runs the evaluation and returns the exit code. Never calls {@code System.exit}. */
    static int run(String[] args, PrintStream out, PrintStream err, CommitInfo commit) {
        int runs = 20;
        int k = 4;
        int lanes = 4;
        long seed = 42;
        List<String> only = new ArrayList<>();
        OptionalInt onlyRun = OptionalInt.empty();
        Path dir = Path.of("eval-harness", "target", "eval");
        Evaluation.EvidenceMode evidence = Evaluation.EvidenceMode.FAILURES;
        boolean requireFull = false;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--mode" -> {
                        String mode = value(args, ++i, a);
                        if (!mode.equals("scripted")) {
                            throw new IllegalArgumentException("mode " + mode + " is not available: only scripted"
                                    + " runs exist before the agent runtime (M5)");
                        }
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
                scenarios.addAll(Scenarios.all());
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

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }
}
