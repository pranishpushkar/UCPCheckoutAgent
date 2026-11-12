package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import dev.ucplab.faultproxy.RuleSet;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * S8 and S14 (13.2, m4-plan section 6; S14 as reworded in D4, with no S14w, Decision 3) in one lane at time scale 4:
 * the expected terminal state, every check of m4-plan section 5 clean, and on the wire exactly what the row describes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutageAndRaceScenariosTest {

    private static final PathPattern COMPLETE = new PathPattern(Scenarios.COMPLETE);
    private static final TimeScale SCALE = TimeScale.of(4);

    private Lane lane;
    private final List<List<RunScript.RaceOutcome>> races = new ArrayList<>();
    private RunScript script;
    private CheckContext checks;

    @BeforeAll
    void startLane(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        lane = Lane.start(0, key, dir);
        script = new RunScript(lane, SCALE, t -> { }, races::add, new Semaphore(ChildProcess.MAX_CHILDREN));
        checks = CheckContext.of(SCALE, key, Scenarios.SHOP, RunScript.POLL);
    }

    @AfterAll
    void stopLane() {
        lane.close();
    }

    private RunEvidence runClean(Scenario s, int run) {
        RunEvidence e = script.run(s, run, Scenarios.seedIndex(s) * 10_000L + run);
        assertThat(e.aborted()).as("%s run %d", s.id(), run).isEmpty();
        assertThat(e.intents()).hasSize(1);
        assertThat(intent(e).state()).as("%s run %d: %s", s.id(), run, intent(e).failureReason())
                .isIn(s.expected());
        assertThat(intent(e).cancelPending()).isFalse();
        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue());
        assertThat(Checks.run(e, checks).all()).as("%s run %d", s.id(), run).isEmpty();
        return e;
    }

    private static PurchaseIntent intent(RunEvidence e) {
        return e.intents().getFirst().intent();
    }

    private static List<LogEntry> completes(RunEvidence e) {
        return e.proxyLog().stream().filter(l -> l.method().equals("POST") && COMPLETE.matches(l.path())).toList();
    }

    // Covers: S14, I1, 7.6, 7.8 (24 workers, each with its own orchestrator, worker id and SQLite connection, call
    // advance at once on the APPROVED intent: exactly one claims it, none fails on a busy database (m4-plan risk 6),
    // exactly one complete reaches the wire, one order)
    @Test
    void s14_threeRuns_twentyFourRacingWorkersSendOneComplete() {
        for (int run = 0; run < 3; run++) {
            races.clear();
            RunEvidence e = runClean(Scenarios.S14, run);

            assertThat(intent(e).state()).isEqualTo(IntentState.COMPLETED);
            assertThat(e.orders()).as("run %d", run).hasSize(1);
            assertThat(completes(e)).as("run %d: one complete on the wire", run).singleElement()
                    .satisfies(l -> assertThat(l.forwards()).isEqualTo(1));
            assertThat(races).as("run %d", run).hasSize(1);
            List<RunScript.RaceOutcome> outcomes = races.getFirst();
            assertThat(outcomes).hasSize(RunScript.RACERS);
            assertThat(outcomes).extracting(RunScript.RaceOutcome::workerId).doesNotHaveDuplicates();
            assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).as(o.workerId()).isEmpty());
            assertThat(outcomes.stream().filter(o -> !o.step().orElseThrow().idle()))
                    .as("run %d: the one racer that claimed the intent", run).singleElement()
                    .satisfies(o -> {
                        // 7.3: the step reports the state after its write-ahead (COMPLETING), then the answer.
                        assertThat(o.step().orElseThrow().before()).isEqualTo(IntentState.COMPLETING);
                        assertThat(o.step().orElseThrow().after()).isEqualTo(IntentState.COMPLETED);
                    });
            Duration slowest = outcomes.stream().map(RunScript.RaceOutcome::took).max(Duration::compareTo)
                    .orElseThrow();
            Duration slowestIdle = outcomes.stream().filter(o -> o.step().orElseThrow().idle())
                    .map(RunScript.RaceOutcome::took).max(Duration::compareTo).orElseThrow();
            System.out.println("S14 run " + run + ": slowest advance " + slowest.toMillis() + " ms, slowest losing"
                    + " advance " + slowestIdle.toMillis() + " ms (busy_timeout 5000 ms)");
        }
    }

    // Covers: S8, I5, 8.2, 8.3, 7.5 (503 on every UCP call for 20 s / k from just after approval: the first complete
    // is Uncertain, reads fail until the window is cleared through the admin API, then the complete is resent under
    // the same key; COMPLETED, or ESCALATED past the deadline; every check clean)
    @Test
    void s8_twoRuns_outageAfterApprovalEndsCompletedOrEscalated() {
        for (int run = 0; run < 2; run++) {
            RunEvidence e = runClean(Scenarios.S8, run);

            List<LogEntry> completes = completes(e);
            assertThat(completes).as("run %d", run).isNotEmpty();
            assertThat(completes.getFirst()).satisfies(l -> {
                assertThat(l.forwards()).isZero();
                assertThat(l.ruleId()).contains("outage-503");
            });
            assertThat(completes).extracting(l -> l.idempotencyKey().orElseThrow())
                    .containsOnly(completes.getFirst().idempotencyKey().orElseThrow());
            List<LogEntry> refused = e.proxyLog().stream().filter(l -> l.ruleId().isPresent()).toList();
            List<LogEntry> after = e.proxyLog().stream().filter(l -> l.seq() > refused.getLast().seq()).toList();
            assertThat(refused).as("run %d: every call in the window was refused before forwarding", run)
                    .allSatisfy(l -> assertThat(l.forwards()).isZero());
            assertThat(refused).anySatisfy(l -> assertThat(l.method()).isEqualTo("GET"));
            Duration window = Duration.between(refused.getFirst().at(), refused.getLast().at());
            assertThat(window).as("run %d: the window lasted no longer than the outage", run)
                    .isLessThanOrEqualTo(SCALE.s8Outage());
            assertThat(after).as("run %d: nothing refused once the window was cleared", run)
                    .allSatisfy(l -> assertThat(l.ruleId()).isEmpty());
            if (intent(e).state() == IntentState.COMPLETED) {
                assertThat(e.orders()).hasSize(1);
                assertThat(completes.getLast().merchantStatus()).isEqualTo(OptionalInt.of(200));
            } else {
                assertThat(e.orders()).isEmpty();
            }
            System.out.println("S8 run " + run + ": " + intent(e).state() + ", " + refused.size() + " calls refused over "
                    + window.toMillis() + " ms, " + completes.size() + " completes");
        }
    }

    // Covers: S8, §12 (the window's rule text is a rule set the admin API accepts)
    @Test
    void s8_outageRules_parseAsOneRuleOnEveryMethodOfTheUcpPaths() {
        RuleSet rules = RuleSet.parse(Scenarios.S8_OUTAGE_RULES);

        assertThat(rules.rules()).singleElement().satisfies(r -> {
            assertThat(r.match().method()).isEmpty();
            assertThat(r.match().fits("GET", "/ucp/v1/checkout-sessions/s-1")).isTrue();
            assertThat(r.match().fits("POST", "/ucp/v1/checkout-sessions/s-1/complete")).isTrue();
            assertThat(r.match().fits("GET", "/.well-known/ucp")).isFalse();
            assertThat(r.times()).isEmpty();
        });
    }
}
