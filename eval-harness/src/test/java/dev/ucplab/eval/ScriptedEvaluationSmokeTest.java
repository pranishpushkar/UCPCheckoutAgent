package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.Report;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * The reduced scripted evaluation that every {@code ./mvnw verify} runs (Decision 1, section 15, m4-plan section 4):
 * every row at N = 20 and time scale 4 on 8 lanes, with S9 at N = 6 and S28 at N = 2 (they start agent child JVMs). It
 * must exit 0: no invariant violation, no unexpected terminal state, no aborted run. It writes
 * {@code target/eval-reduced/report.{json,md}}, whose first line says REDUCED, and {@code margins.txt}: the smallest
 * slack each real-time race had in this run (m4-plan risk 2).
 */
class ScriptedEvaluationSmokeTest {

    static final Path OUT = Path.of("target", "eval-reduced");
    static final TimeScale SCALE = TimeScale.of(4);

    // Covers: 13.5, 15, NFR1, S0 to S9, S11, S13, S14, S28, I1, I2, I3, I4, I5, I7 (the reduced report is clean)
    @Test
    void reducedEvaluation_everyRow_exitsZeroWithReducedBanner() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "S9 and S28 need kill -9");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ConcurrentLinkedQueue<RunEvidence> runs = new ConcurrentLinkedQueue<>();
        EvalConfig config = new EvalConfig(20, SCALE, 8, 42, Map.of("S9", 6, "S28", 2));
        long t0 = System.nanoTime();
        Report report = new Evaluation(config, Scenarios.all(), OptionalInt.empty(), OUT,
                Evaluation.EvidenceMode.FAILURES, t -> { }, runs::add, key)
                .run(CommitInfo.detect(Path.of("").toAbsolutePath()));
        Duration took = Duration.ofNanos(System.nanoTime() - t0);

        String margins = margins(List.copyOf(runs)) + "wall time " + took.toMillis() + " ms for " + runs.size()
                + " runs on " + config.lanes() + " lanes\n";
        Files.writeString(OUT.resolve("margins.txt"), margins, StandardCharsets.UTF_8);
        System.out.println(report.reducedBanner() + "\n" + margins);

        assertThat(report.exitCode()).as("violations %d, unexpected %d, aborted %d; see %s", report.violations(),
                report.unexpected(), report.aborted(), OUT.resolve("report.md")).isZero();
        assertThat(report.scenarios()).hasSize(Scenarios.all().size());
        assertThat(report.scenarios()).allSatisfy(s -> assertThat(s.n()).isEqualTo(config.runsOf(s.scenario().id())));
        String md = Files.readString(OUT.resolve("report.md"), StandardCharsets.UTF_8);
        String json = Files.readString(OUT.resolve("report.json"), StandardCharsets.UTF_8);
        assertThat(md.lines().findFirst()).hasValueSatisfying(l -> assertThat(l).contains(report.reducedBanner()));
        assertThat(report.reducedBanner()).isEqualTo(
                "REDUCED: 20 runs per scenario (S9 6, S28 2), time scale 4 — not an acceptance report");
        assertThat(json).contains("\"reduced\": true");
    }

    /**
     * The smallest slack, over the runs of this evaluation, of each race between real-time events that a row relies
     * on (m4-plan section 3 and risk 2). Each line names the race, its configured margin and the observed one.
     */
    static String margins(List<RunEvidence> runs) {
        Duration tc = SCALE.completeTimeout();
        Duration deadline = SCALE.orchestrator().reconcileDeadline();
        Map<String, Duration> smallest = new TreeMap<>();
        for (RunEvidence e : runs) {
            if (e.intents().size() != 1) {
                continue;
            }
            PurchaseIntent intent = e.intents().getFirst().intent();
            Optional<Instant> end = terminalAt(e.intents().getFirst().events());
            switch (e.scenarioId()) {
                case "S3" -> firstComplete(e).ifPresent(a -> a.respondedAt().ifPresent(r -> keep(smallest,
                        "S3 hold - client wait on the complete (configured " + ms(SCALE.s3Hold().minus(tc)) + ")",
                        SCALE.s3Hold().minus(Duration.between(a.sentAt(), r)))));
                // S9 K3 is the tight one: the kill comes after uncertain_since, so the takeover (lease + period) must
                // still fit inside the deadline; configured, deadline - (lease + period).
                case "S8", "S9" -> intent.uncertainSince().ifPresent(u -> end.ifPresent(t -> keep(smallest,
                        e.scenarioId() + " reconcile deadline - time from uncertain_since to " + intent.state()
                                + (e.scenarioId().equals("S9") ? " (configured for K3 "
                                + ms(deadline.minus(SCALE.lease()).minus(SCALE.workerPeriod())) + ")" : ""),
                        Duration.between(t, u.plus(deadline)))));
                case "S28" -> intent.uncertainSince().ifPresent(u -> end.ifPresent(t -> keep(smallest,
                        "S28 takeover after the reconcile deadline (configured "
                                + ms(SCALE.lease().dividedBy(2).minus(SCALE.orchestrator().pollMax())) + ")",
                        Duration.between(u.plus(deadline), t))));
                default -> { }
            }
        }
        StringBuilder sb = new StringBuilder("smallest observed margins at time scale " + SCALE.k() + " (m4-plan risk"
                + " 2):\n");
        smallest.forEach((race, d) -> sb.append("  ").append(race).append(": ").append(ms(d)).append('\n'));
        return sb.toString();
    }

    private static void keep(Map<String, Duration> smallest, String race, Duration d) {
        smallest.merge(race, d, (a, b) -> a.compareTo(b) <= 0 ? a : b);
    }

    private static Optional<MerchantAttempt> firstComplete(RunEvidence e) {
        return e.intents().getFirst().attempts().stream().filter(a -> a.op() == AttemptOp.COMPLETE).findFirst();
    }

    /** When D's transition events say the intent became terminal. */
    private static Optional<Instant> terminalAt(List<IntentEvent> events) {
        List<Instant> at = new ArrayList<>();
        for (IntentEvent ev : events) {
            if (ev.type().equals("transition")
                    && IntentState.valueOf((String) CanonicalJson.readObject(ev.payloadJson()).get("to")).isTerminal()) {
                at.add(ev.at());
            }
        }
        return at.stream().findFirst();
    }

    private static String ms(Duration d) {
        return d.toMillis() + " ms";
    }
}
