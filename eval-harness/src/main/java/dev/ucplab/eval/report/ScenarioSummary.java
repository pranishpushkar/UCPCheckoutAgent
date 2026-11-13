package dev.ucplab.eval.report;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.eval.Scenario;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.check.InvariantCheck;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One scenario's row of the report (13.3, m4-plan section 11): its runs and what they add up to. */
public record ScenarioSummary(Scenario scenario, List<RunResult> runs) {

    public ScenarioSummary {
        Objects.requireNonNull(scenario, "scenario");
        runs = runs.stream().sorted(Comparator.comparingInt(RunResult::run)).toList();
        if (runs.isEmpty()) {
            throw new IllegalArgumentException(scenario.id() + ": a scenario row needs at least one run");
        }
    }

    public int n() {
        return runs.size();
    }

    public int aborted() {
        return (int) runs.stream().filter(r -> r.aborted().isPresent()).count();
    }

    /** Every terminal state, in table 7.1 order, with how many runs ended in it (zero included). */
    public Map<IntentState, Integer> terminal() {
        Map<IntentState, Integer> out = new EnumMap<>(IntentState.class);
        for (IntentState s : IntentState.values()) {
            if (s.isTerminal()) {
                out.put(s, 0);
            }
        }
        runs.forEach(r -> r.terminal().filter(IntentState::isTerminal).ifPresent(s -> out.merge(s, 1, Integer::sum)));
        return out;
    }

    public int expectedOutcomes() {
        return (int) runs.stream().filter(RunResult::expected).count();
    }

    public int unexpected() {
        return (int) runs.stream().filter(r -> r.aborted().isEmpty() && !r.expected()).count();
    }

    /** Runs with at least one violation of each invariant, by id, for every check the harness runs. */
    public Map<String, Integer> violations() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (InvariantCheck c : Checks.all()) {
            out.put(c.id(), (int) runs.stream().filter(r -> !r.checks().violations(c.id()).isEmpty()).count());
        }
        return out;
    }

    /** Runs with any violation. */
    public int violatedRuns() {
        return (int) runs.stream().filter(RunResult::violated).count();
    }

    /** The checks run beyond the scenario's required invariants. */
    public List<String> alsoChecked() {
        return Checks.all().stream().map(InvariantCheck::id)
                .filter(id -> !scenario.requiredInvariants().contains(id)).toList();
    }

    public Stats.Interval expectedOutcomeInterval() {
        return Stats.wilson(expectedOutcomes(), n());
    }

    /** The 95% upper bound on the rate of runs with any violation. */
    public Stats.Bound violationBound() {
        return Stats.violationBound(violatedRuns(), n());
    }

    public List<Duration> timesToTerminal() {
        return runs.stream().flatMap(r -> r.timeToTerminal().stream()).toList();
    }

    public List<Duration> reconcileTimes() {
        return runs.stream().flatMap(r -> r.reconcile().stream()).toList();
    }

    public Optional<Duration> maxTimeToTerminal() {
        return timesToTerminal().stream().max(Comparator.naturalOrder());
    }

    /** One entry per failed run and kind: aborted, each violated invariant, or an unexpected outcome. */
    public List<Failure> failures() {
        List<Failure> out = new ArrayList<>();
        for (RunResult r : runs) {
            if (r.aborted().isPresent()) {
                out.add(new Failure(r, "aborted", Optional.empty(), r.aborted().get()));
                continue;
            }
            r.checks().byInvariant().forEach((id, v) -> {
                if (!v.isEmpty()) {
                    out.add(new Failure(r, "violation", Optional.of(id),
                            String.join("; ", v.stream().map(x -> x.detail()).toList())));
                }
            });
            if (!r.expected()) {
                out.add(new Failure(r, "unexpected", Optional.empty(), "ended "
                        + r.terminal().map(Enum::name).orElse("with no intent") + " with " + r.intents()
                        + " intent(s); expected " + scenario.expected().stream().map(Enum::name).sorted().toList()));
            }
        }
        return out;
    }

    /** One failure of one run, for the failure-analysis section. */
    public record Failure(RunResult run, String kind, Optional<String> invariant, String detail) {
    }
}
