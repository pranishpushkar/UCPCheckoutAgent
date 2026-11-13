package dev.ucplab.eval.report;

import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.check.InvariantCheck;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code report.md} (m4-plan section 11): header, scenario table, invariants table, failure analysis, method,
 * configuration, deferred items. A reduced report says so in its first line (13.5). Never holds a body, token or key.
 */
public final class ReportMarkdown {

    private ReportMarkdown() {
    }

    public static String write(Report r) {
        StringBuilder sb = new StringBuilder();
        if (r.reduced()) {
            sb.append("**").append(r.reducedBanner()).append("**\n\n");
        }
        sb.append("# Scripted evaluation report\n\n");
        line(sb, "Mode", Report.MODE + "; rows: " + r.modes().stream().map(Mode::label)
                .collect(Collectors.joining(", ")));
        line(sb, "Commit", "`" + r.commit().sha() + "` on " + r.commit().branch()
                + (r.commit().dirty() ? " (dirty: uncommitted changes)" : "") + ", from " + r.commit().source());
        line(sb, "Generated", r.generatedAt().toString());
        line(sb, "Runs per scenario", r.config().runsPerScenario() + (r.config().fewerRuns().isEmpty() ? ""
                : " (fewer: " + r.config().fewerRunsText() + ")"));
        line(sb, "Time scale k", Integer.toString(r.config().timeScale().k()));
        line(sb, "Lanes", Integer.toString(r.config().lanes()));
        line(sb, "Base seed", Long.toString(r.config().baseSeed()));
        line(sb, "Runtime", String.join("; ", r.runtime()));
        r.prices().ifPresent(p -> line(sb, "Prices", p.model() + ", " + p.inputPerMillionTokens() + " / "
                + p.outputPerMillionTokens() + " " + p.currency() + " per million input / output tokens, as of "
                + p.date() + " (" + p.source() + ")"));
        sb.append('\n');

        sb.append("## Scenarios\n\n");
        sb.append("| ID | Mode | Fault | Expected | Runs | Terminal states | Expected outcome (Wilson 95%) | Violations"
                + " | 95% upper bound | Time to terminal p50/p95 | Reconciliations, resolve p50/p95"
                + " | Model calls p50/p95 | Tool calls p50/p95 | Tokens p50/p95 | Cost per completed purchase"
                + " | Trace links |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ScenarioSummary s : r.scenarios()) {
            Stats.Interval w = s.expectedOutcomeInterval();
            Stats.Bound b = s.violationBound();
            String terminal = s.terminal().entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(e -> e.getKey().name() + " " + e.getValue()).collect(Collectors.joining(", "));
            if (s.aborted() > 0) {
                terminal = (terminal.isEmpty() ? "" : terminal + ", ") + "aborted " + s.aborted();
            }
            sb.append("| ").append(s.scenario().id()).append(" | ").append(s.mode().label())
                    .append(" | ").append(cell(s.scenario().fault()))
                    .append(" | ").append(cell(s.scenario().expectedText()))
                    .append(" | ").append(s.n())
                    .append(" | ").append(terminal.isEmpty() ? "none" : terminal)
                    .append(" | ").append(s.expectedOutcomes()).append('/').append(s.n()).append(" (")
                    .append(JsonOut.number(w.low())).append("–").append(JsonOut.number(w.high())).append(')')
                    .append(" | ").append(s.violatedRuns())
                    .append(" | ").append(JsonOut.number(b.value())).append(" (").append(b.method()).append(')')
                    .append(" | ").append(pair(s.timesToTerminal()))
                    .append(" | ").append(s.reconcileTimes().size()).append(", ").append(pair(s.reconcileTimes()))
                    .append(" | ").append(counts(s, s.modelCalls()))
                    .append(" | ").append(counts(s, s.toolCalls()))
                    .append(" | ").append(tokens(s))
                    .append(" | ").append(cost(s, r))
                    .append(" | ").append(traces(s)).append(" |\n");
        }
        sb.append('\n');

        sb.append("## Invariants\n\n");
        sb.append("| Invariant | Scenarios | Runs | Violations | 95% upper bound (method) |\n");
        sb.append("|---|---|---|---|---|\n");
        for (InvariantCheck c : Checks.all()) {
            int runs = r.scenarios().stream().mapToInt(ScenarioSummary::n).sum();
            int v = r.scenarios().stream().mapToInt(s -> s.violations().get(c.id())).sum();
            Stats.Bound b = Stats.violationBound(v, Math.max(1, runs));
            sb.append("| ").append(c.id()).append(" | ").append(r.scenarios().size()).append(" | ").append(runs)
                    .append(" | ").append(v).append(" | ").append(JsonOut.number(b.value())).append(" (")
                    .append(b.method()).append(") |\n");
        }
        sb.append('\n');

        sb.append("## Failure analysis\n\n");
        List<ScenarioSummary.Failure> failures = r.scenarios().stream().flatMap(s -> s.failures().stream()).toList();
        if (failures.isEmpty()) {
            sb.append("No violations, unexpected outcomes or aborted runs.\n\n");
        }
        for (ScenarioSummary.Failure f : failures) {
            RunResult run = f.run();
            sb.append("### ").append(run.scenarioId()).append(" run ").append(run.run()).append(": ")
                    .append(f.kind()).append(f.invariant().map(i -> " of " + i).orElse("")).append("\n\n");
            sb.append("- Seed ").append(run.seed()).append(", lane ").append(run.lane()).append('\n');
            sb.append("- ").append(f.detail()).append('\n');
            sb.append("- Evidence: ").append(run.evidence().map(p -> "`" + p + "`").orElse("not written"))
                    .append("\n\n");
        }

        sb.append("## Method\n\n");
        r.methodNotes().forEach(n -> sb.append("- ").append(n).append('\n'));
        sb.append('\n');

        sb.append("## Configuration\n\n```json\n");
        sb.append(configJson(r));
        sb.append("```\n\n");
        sb.append("Exit code ").append(r.exitCode()).append(": ").append(r.violations()).append(" runs with"
                + " violations, ").append(r.unexpected()).append(" unexpected outcomes, ").append(r.aborted())
                .append(" aborted.\n\n");

        sb.append("## Deferred\n\n");
        sb.append("- Live agent rows (agent-live) and their tokens and cost: the live report (M5 C16, C18).\n");
        sb.append("- The I8 canary grep across logs and traces: M5 C14.\n");
        return sb.toString();
    }

    /** At most this many trace links per row in the table; report.json lists them all. */
    static final int TRACE_LINKS_SHOWN = 3;

    private static String counts(ScenarioSummary s, List<Long> values) {
        if (s.mode() == Mode.ORCHESTRATOR_DIRECT) {
            return Report.NA_DIRECT;
        }
        Optional<Long> p50 = Stats.percentileOf(values, 50);
        return p50.isEmpty() ? "–" : p50.get() + "/" + Stats.percentileOf(values, 95).orElseThrow();
    }

    private static String tokens(ScenarioSummary s) {
        return switch (s.mode()) {
            case ORCHESTRATOR_DIRECT -> Report.NA_DIRECT;
            case AGENT_SCRIPTED -> Report.NA_SCRIPTED;
            case AGENT_LIVE -> counts(s, s.tokens());
        };
    }

    private static String cost(ScenarioSummary s, Report r) {
        return switch (s.mode()) {
            case ORCHESTRATOR_DIRECT -> Report.NA_DIRECT;
            case AGENT_SCRIPTED -> Report.NA_SCRIPTED;
            case AGENT_LIVE -> r.prices().map(p -> s.costPerCompletedPurchase(p).map(c -> c.toPlainString() + " "
                    + p.currency()).orElse(Report.NA_NOT_COMPLETED)).orElse(Report.NA_NO_PRICES);
        };
    }

    private static String traces(ScenarioSummary s) {
        if (s.mode() == Mode.ORCHESTRATOR_DIRECT) {
            return Report.NA_DIRECT;
        }
        List<String> all = s.traces();
        if (all.isEmpty()) {
            return Report.NA_NO_EVIDENCE;
        }
        String shown = all.stream().limit(TRACE_LINKS_SHOWN).map(p -> "[" + p + "](" + p + ")")
                .collect(Collectors.joining(", "));
        return all.size() > TRACE_LINKS_SHOWN ? shown + " and " + (all.size() - TRACE_LINKS_SHOWN) + " more" : shown;
    }

    private static String configJson(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commit", ReportJson.commit(r));
        m.put("config", ReportJson.config(r));
        return JsonOut.write(m);
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append("- **").append(key).append(":** ").append(value).append('\n');
    }

    private static String pair(List<Duration> values) {
        Optional<Duration> p50 = Stats.percentile(values, 50);
        Optional<Duration> p95 = Stats.percentile(values, 95);
        return p50.isEmpty() ? "–" : p50.get().toMillis() + "/" + p95.orElseThrow().toMillis() + " ms";
    }

    private static String cell(String s) {
        return s.replace("|", "\\|");
    }
}
