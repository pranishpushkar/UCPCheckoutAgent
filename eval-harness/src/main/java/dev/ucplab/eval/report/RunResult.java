package dev.ucplab.eval.report;

import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.eval.RunEvidence;
import dev.ucplab.eval.Scenario;
import dev.ucplab.eval.check.CheckResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the report keeps of one run: its identity, how it ended, what the checks found, and its timings. The terminal
 * state is the agent's stored state (D), which is what the expected outcome is about; whether it is right is the
 * checks' business, on ground truth.
 *
 * @param terminal the final state of the run's intent, or empty if it has none (an aborted run may have none)
 * @param intents how many intents the run left; every M4 row expects exactly one
 * @param reconcile from entering RECONCILING to leaving it, on the agent's clock (D), if the run reconciled
 * @param evidence where the run's evidence folder was written, relative to the report, if it was
 */
public record RunResult(String scenarioId, int run, long seed, int lane, Optional<String> aborted,
                        Optional<IntentState> terminal, int intents, boolean expected, CheckResult checks,
                        Optional<Duration> timeToTerminal, Optional<Duration> reconcile, Optional<String> evidence) {

    public RunResult {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(aborted, "aborted");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(checks, "checks");
        Objects.requireNonNull(timeToTerminal, "timeToTerminal");
        Objects.requireNonNull(reconcile, "reconcile");
        Objects.requireNonNull(evidence, "evidence");
    }

    public static RunResult of(Scenario scenario, RunEvidence e, CheckResult checks) {
        Optional<IntentState> terminal = e.intents().isEmpty() ? Optional.empty()
                : Optional.of(e.intents().getFirst().intent().state());
        boolean expected = e.aborted().isEmpty() && e.intents().size() == 1
                && terminal.map(scenario.expected()::contains).orElse(false);
        Optional<Duration> reconcile = e.intents().isEmpty() ? Optional.empty()
                : reconcileSpan(e.intents().getFirst().events());
        return new RunResult(e.scenarioId(), e.run(), e.seed(), e.lane(), e.aborted(), terminal, e.intents().size(),
                expected, checks, e.timeToTerminal(), reconcile, Optional.empty());
    }

    public RunResult withEvidence(String path) {
        return new RunResult(scenarioId, run, seed, lane, aborted, terminal, intents, expected, checks, timeToTerminal,
                reconcile, Optional.of(path));
    }

    public boolean violated() {
        return !checks.clean();
    }

    /** Whether the run is a failure of any kind: aborted, a violation, or an unexpected outcome. */
    public boolean failed() {
        return aborted.isPresent() || violated() || !expected;
    }

    /** The first entry into RECONCILING to the first transition out of COMPLETING and RECONCILING after it. */
    private static Optional<Duration> reconcileSpan(List<IntentEvent> events) {
        Instant entered = null;
        for (IntentEvent ev : events) {
            if (!ev.type().equals("transition")) {
                continue;
            }
            Map<String, Object> t = CanonicalJson.readObject(ev.payloadJson());
            String to = (String) t.get("to");
            if (entered == null && "RECONCILING".equals(to)) {
                entered = ev.at();
            } else if (entered != null && !"RECONCILING".equals(to) && !"COMPLETING".equals(to)) {
                return Optional.of(Duration.between(entered, ev.at()));
            }
        }
        return Optional.empty();
    }
}
