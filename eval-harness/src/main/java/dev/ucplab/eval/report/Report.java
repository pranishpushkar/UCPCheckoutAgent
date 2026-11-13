package dev.ucplab.eval.report;

import dev.ucplab.eval.EvalConfig;
import dev.ucplab.eval.check.Checks;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One evaluation's report (13.5, m4-plan section 11), before it is written as JSON or Markdown. It is reduced unless
 * every row has at least {@value #FULL_RUNS} runs at time scale 1.
 *
 * @param merchantVersion the UCP version the in-process merchant speaks
 * @param runtime the Java version and the operating system, as one line each
 * @param prices the price file for the live rows' cost column, if one was given (13.3)
 */
public record Report(Instant generatedAt, CommitInfo commit, EvalConfig config, String merchantVersion,
                     List<String> runtime, List<ScenarioSummary> scenarios, Optional<PriceTable> prices) {

    /** 13.5: the acceptance report has 200 runs per scenario. */
    public static final int FULL_RUNS = 200;

    /** The exit codes of 13.5. */
    public static final int CLEAN = 0;
    public static final int VIOLATION_OR_ABORT = 1;
    public static final int UNEXPECTED_OUTCOME = 2;
    public static final int HARNESS_ERROR = 3;

    public Report {
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(merchantVersion, "merchantVersion");
        runtime = List.copyOf(runtime);
        scenarios = List.copyOf(scenarios);
        Objects.requireNonNull(prices, "prices");
    }

    /** A report with no price file: live rows' cost reads {@link #NA_NO_PRICES}. */
    public Report(Instant generatedAt, CommitInfo commit, EvalConfig config, String merchantVersion,
                  List<String> runtime, List<ScenarioSummary> scenarios) {
        this(generatedAt, commit, config, merchantVersion, runtime, scenarios, Optional.empty());
    }

    /** The modes of the rows, in {@link Mode} order: the header names the set (m5-plan section 9). */
    public List<Mode> modes() {
        return Arrays.stream(Mode.values())
                .filter(m -> scenarios.stream().anyMatch(s -> s.mode() == m)).toList();
    }

    public boolean reduced() {
        return config.timeScale().k() != 1 || scenarios.isEmpty()
                || scenarios.stream().anyMatch(s -> s.n() < FULL_RUNS);
    }

    /** The first line of a reduced report (13.5: a reduced report says so in its first line). */
    public String reducedBanner() {
        int fewest = scenarios.stream().mapToInt(ScenarioSummary::n).min().orElse(0);
        int most = scenarios.stream().mapToInt(ScenarioSummary::n).max().orElse(0);
        String fewer = scenarios.stream().filter(s -> s.n() < most).map(s -> s.scenario().id() + " " + s.n())
                .collect(java.util.stream.Collectors.joining(", "));
        return "REDUCED: " + (fewest == most ? fewest + " runs per scenario"
                : most + " runs per scenario (" + fewer + ")") + ", time scale " + config.timeScale().k()
                + " — not an acceptance report";
    }

    public int violations() {
        return scenarios.stream().mapToInt(ScenarioSummary::violatedRuns).sum();
    }

    public int aborted() {
        return scenarios.stream().mapToInt(ScenarioSummary::aborted).sum();
    }

    public int unexpected() {
        return scenarios.stream().mapToInt(ScenarioSummary::unexpected).sum();
    }

    /** 1 for any violation or aborted run, else 2 for any unexpected terminal state, else 0. */
    public int exitCode() {
        if (violations() > 0 || aborted() > 0) {
            return VIOLATION_OR_ABORT;
        }
        return unexpected() > 0 ? UNEXPECTED_OUTCOME : CLEAN;
    }

    /** The evaluation's mode, as 13.1 asks every number to be labelled; each row also has its {@link Mode}. */
    public static final String MODE = "scripted";
    /** Why an agent column is empty, by cause (m5-plan section 9). */
    public static final String NA_DIRECT = "n/a (orchestrator-direct)";
    public static final String NA_SCRIPTED = "n/a (scripted model)";
    public static final String NA_NO_EVIDENCE = "n/a (evidence for failed runs only)";
    public static final String NA_NO_PRICES = "n/a (no price file)";
    public static final String NA_NOT_COMPLETED = "n/a (no completed purchase)";

    /** How the numbers were produced, for the method section of both formats. */
    public List<String> methodNotes() {
        List<String> notes = new ArrayList<>(List.of(
                "Orchestrator-direct rows (13.1 M4 note): each run calls Orchestrator.propose with the scenario's"
                        + " fixed basket; an automatic approver decides through the approval service; the recovery"
                        + " worker drives the intent. There is no model, so the agent columns read " + NA_DIRECT
                        + ".",
                "Agent rows (13.1 D6, m5-plan section 9): the agent runtime proposes, driven by a ScriptedModel"
                        + " (agent-scripted) or a live model (agent-live); the approver and the recovery worker then"
                        + " act as in the orchestrator-direct rows. Model and tool calls are the run record's counts."
                        + " Tokens and cost per completed purchase are for live rows only (scripted rows read "
                        + NA_SCRIPTED + "): tokens times the price file, with its model and date (13.3, 13.4)."
                        + " Trace links are each run's trace.jsonl, for runs with an evidence folder.",
                "Real time throughout, every duration divided by the time scale k = " + config.timeScale().k()
                        + " (13.1); approval lifetime and sign-in age are not scaled.",
                "Each run gets a fresh database, gateway, circuit breakers, worker id and user, and the merchant is"
                        + " reset before it; runs in one lane are sequential, lanes share nothing.",
                "Policy usage is a harness stub with no earlier spend or proposals: every run has a user of its own,"
                        + " so there is nothing to count. A real PolicyUsage is out of scope for M4 (it belongs with"
                        + " agent-service in M6).",
                "Invariants are checked on ground truth the agent does not control: O (/_lab/orders), G (the merchant's"
                        + " own GET of each order's session), L (the fault proxy's log in front of the merchant,"
                        + " including Idempotency-Key and a keyed body hash) and A (the approver's own decisions). An"
                        + " aborted run fails every check.",
                "Terminal states, the expected outcome and reconciliation times come from the agent's stored state"
                        + " (D), which is what they describe; time to terminal is the harness's own observation."));
        Checks.all().forEach(c -> notes.add(c.id() + " (" + c.description() + "): "
                + c.evidence() + "."));
        return notes;
    }

    /** Why this report does not meet {@code --require-full}: empty when it does. */
    public List<String> requireFullProblems() {
        List<String> out = new ArrayList<>();
        if (config.timeScale().k() != 1) {
            out.add("time scale " + config.timeScale().k() + ", not 1");
        }
        if (scenarios.isEmpty()) {
            out.add("no scenarios");
        }
        scenarios.stream().filter(s -> s.n() < FULL_RUNS)
                .forEach(s -> out.add(s.scenario().id() + " has " + s.n() + " runs, fewer than " + FULL_RUNS));
        if (!commit.known()) {
            out.add("the commit is unknown");
        }
        if (commit.dirty()) {
            out.add("the working tree has uncommitted changes");
        }
        return out;
    }
}
