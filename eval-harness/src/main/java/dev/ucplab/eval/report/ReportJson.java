package dev.ucplab.eval.report;

import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.check.InvariantCheck;
import dev.ucplab.ucp.UcpConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code report.json}, schema {@value #SCHEMA} (m4-plan section 11). Never holds a body, token or key. */
public final class ReportJson {

    public static final String SCHEMA = "ucplab.eval.report/1";
    private static final String UCP_AGENT = "profile=\"https://eval.ucplab.dev/agent\"";

    private ReportJson() {
    }

    public static String write(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        if (r.reduced()) {
            m.put("banner", r.reducedBanner());
        }
        m.put("generatedAt", r.generatedAt().toString());
        m.put("mode", Report.MODE);
        m.put("driver", Report.DRIVER);
        m.put("reduced", r.reduced());
        m.put("commit", commit(r));
        m.put("config", config(r));
        m.put("method", r.methodNotes());
        List<Object> scenarios = new ArrayList<>();
        r.scenarios().forEach(s -> scenarios.add(scenario(s)));
        m.put("scenarios", scenarios);
        m.put("invariants", invariants(r));
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("code", r.exitCode());
        exit.put("violations", r.violations());
        exit.put("unexpectedOutcomes", r.unexpected());
        exit.put("aborted", r.aborted());
        m.put("exit", exit);
        return JsonOut.write(m);
    }

    static Map<String, Object> commit(Report r) {
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("sha", r.commit().sha());
        commit.put("branch", r.commit().branch());
        commit.put("dirty", r.commit().dirty());
        commit.put("source", r.commit().source());
        return commit;
    }

    static Map<String, Object> config(Report r) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("runsPerScenario", r.config().runsPerScenario());
        if (!r.config().fewerRuns().isEmpty()) {
            c.put("fewerRuns", new java.util.TreeMap<String, Object>(r.config().fewerRuns()));
        }
        c.put("timeScale", r.config().timeScale().k());
        c.put("lanes", r.config().lanes());
        c.put("baseSeed", r.config().baseSeed());
        UcpConfig u = r.config().timeScale().ucp(UCP_AGENT);
        Map<String, Object> ucp = new LinkedHashMap<>();
        ucp.put("connectTimeoutMs", ms(u.connectTimeout()));
        ucp.put("requestTimeoutMs", ms(u.requestTimeout()));
        ucp.put("completeTimeoutMs", ms(u.completeTimeout()));
        ucp.put("maxRetries", u.maxRetries());
        ucp.put("backoffInitialMs", ms(u.backoffInitial()));
        ucp.put("backoffCapMs", ms(u.backoffCap()));
        ucp.put("breakerThreshold", u.breakerThreshold());
        ucp.put("breakerOpenForMs", ms(u.breakerOpenFor()));
        c.put("ucp", ucp);
        OrchestratorConfig o = r.config().timeScale().orchestrator();
        Map<String, Object> orch = new LinkedHashMap<>();
        orch.put("leaseMs", ms(o.lease()));
        orch.put("completeTimeoutMs", ms(o.completeRequestTimeout()));
        orch.put("resendQuietPeriodMs", ms(o.resendQuietPeriod()));
        orch.put("reconcileDeadlineMs", ms(o.reconcileDeadline()));
        orch.put("maxCompleteResends", o.maxCompleteResends());
        orch.put("pollInitialMs", ms(o.pollInitial()));
        orch.put("pollMaxMs", ms(o.pollMax()));
        orch.put("pollJitter", o.pollJitter());
        c.put("orchestrator", orch);
        RecoveryConfig rc = r.config().timeScale().recovery();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("periodMs", ms(rc.period()));
        rec.put("limit", rc.limit());
        rec.put("maxStepsPerIntent", rc.maxStepsPerIntent());
        c.put("recovery", rec);
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("ucpVersion", r.merchantVersion());
        merchant.put("module", "dev.ucplab:merchant (in process, same commit)");
        c.put("merchant", merchant);
        c.put("runtime", r.runtime());
        return c;
    }

    private static Map<String, Object> scenario(ScenarioSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.scenario().id());
        m.put("title", s.scenario().title());
        m.put("fault", s.scenario().fault());
        m.put("expected", s.scenario().expected().stream().map(Enum::name).sorted().toList());
        m.put("requiredInvariants", s.scenario().requiredInvariants());
        m.put("alsoChecked", s.alsoChecked());
        m.put("runs", s.n());
        m.put("aborted", s.aborted());
        Map<String, Object> terminal = new LinkedHashMap<>();
        s.terminal().forEach((state, n) -> terminal.put(state.name(), n));
        m.put("terminal", terminal);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("k", s.expectedOutcomes());
        outcome.put("n", s.n());
        outcome.put("rate", (double) s.expectedOutcomes() / s.n());
        Stats.Interval w = s.expectedOutcomeInterval();
        outcome.put("wilson95", List.of(w.low(), w.high()));
        m.put("expectedOutcome", outcome);
        m.put("violations", new LinkedHashMap<String, Object>(s.violations()));
        m.put("violationUpperBound95", bound(s.violationBound()));
        m.put("timeToTerminalMs", times(s.timesToTerminal(), true));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("runs", s.reconcileTimes().size());
        rec.put("timeToResolveMs", times(s.reconcileTimes(), false));
        m.put("reconciliation", rec);
        m.put("modelCalls", JsonOut.NULL);
        m.put("tokens", JsonOut.NULL);
        m.put("costPerCompletedPurchase", JsonOut.NULL);
        m.put("traces", JsonOut.NULL);
        m.put("deferred", Report.NO_AGENT);
        List<Object> failures = new ArrayList<>();
        for (ScenarioSummary.Failure f : s.failures()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("run", f.run().run());
            x.put("seed", f.run().seed());
            x.put("lane", f.run().lane());
            x.put("kind", f.kind());
            f.invariant().ifPresent(i -> x.put("invariant", i));
            x.put("detail", f.detail());
            x.put("evidence", f.run().evidence().<Object>map(p -> p).orElse(JsonOut.NULL));
            failures.add(x);
        }
        m.put("failures", failures);
        return m;
    }

    private static List<Object> invariants(Report r) {
        List<Object> out = new ArrayList<>();
        for (InvariantCheck c : Checks.all()) {
            int scenarios = 0;
            int runs = 0;
            int violations = 0;
            for (ScenarioSummary s : r.scenarios()) {
                scenarios++;
                runs += s.n();
                violations += s.violations().get(c.id());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("scenarios", scenarios);
            m.put("runs", runs);
            m.put("violations", violations);
            m.put("upperBound95", runs == 0 ? JsonOut.NULL : bound(Stats.violationBound(violations, runs)));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> bound(Stats.Bound b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", b.method());
        m.put("value", b.value());
        return m;
    }

    private static Object times(List<Duration> values, boolean withMax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50", msOrNull(Stats.percentile(values, 50)));
        m.put("p95", msOrNull(Stats.percentile(values, 95)));
        if (withMax) {
            m.put("max", msOrNull(values.stream().max(Duration::compareTo)));
        }
        return m;
    }

    private static Object msOrNull(Optional<Duration> d) {
        return d.<Object>map(ReportJson::ms).orElse(JsonOut.NULL);
    }

    private static long ms(Duration d) {
        return d.toMillis();
    }
}
