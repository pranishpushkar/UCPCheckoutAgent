package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * S9 at each kill point K1 to K3, and S28 (13.2, m4-plan section 8), in one lane at time scale 4, with a real kill -9
 * of the agent child JVM: exit value 137, a restart on the same database file with a new worker id, the expected
 * terminal state, and every check clean with the crash counted in the 7.6 bounds ({@code CheckContext.withCrashes}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KillScenariosTest {

    private static final PathPattern COMPLETE = new PathPattern(Scenarios.COMPLETE);

    private Lane lane;
    private RunScript script;
    private CheckContext checks;

    @BeforeAll
    void startLane(@TempDir Path dir) throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "kill -9 needs a POSIX platform");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        lane = Lane.start(0, key, dir);
        script = new RunScript(lane, TimeScale.of(4));
        checks = CheckContext.of(TimeScale.of(4), key, Scenarios.SHOP, RunScript.POLL);
    }

    @AfterAll
    void stopLane() {
        if (lane != null) {
            lane.close();
        }
    }

    /** One run: killed once with SIGKILL, restarted on the same file under a new worker id, every check clean. */
    private RunEvidence runKilled(Scenario s, int run) {
        long t0 = System.nanoTime();
        RunEvidence e = script.run(s, run, Scenarios.seedIndex(s) * 10_000L + run);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(e.aborted()).as("%s run %d", s.id(), run).isEmpty();
        assertThat(e.intents()).hasSize(1);
        PurchaseIntent intent = e.intents().getFirst().intent();
        assertThat(intent.state()).as("%s run %d: %s", s.id(), run, intent.failureReason()).isIn(s.expected());

        assertThat(e.crashes()).isEqualTo(1);
        assertThat(e.children()).hasSize(2);
        RunEvidence.Child killed = e.children().get(0);
        RunEvidence.Child restarted = e.children().get(1);
        assertThat(killed.killed()).isTrue();
        // 137 = 128 + SIGKILL is the JDK's native exit mapping (ChildProcess#kill); seen on macOS and on Linux (CI run
        // https://github.com/pranishpushkar/UCPCheckoutAgent/actions/runs/43337144864).
        assertThat(killed.exitValue()).as("%s run %d: exit value after destroyForcibly", s.id(), run)
                .isEqualTo(ChildProcess.SIGKILL_EXIT);
        assertThat(restarted.killed()).isFalse();
        assertThat(restarted.dbFile()).as("the restart opens the same database file").isEqualTo(killed.dbFile());
        assertThat(restarted.workerId()).as("a new worker id per process start (7.6)").isNotEqualTo(killed.workerId())
                .startsWith("worker-");
        // The SQLite driver warns on stderr that SLF4J has no provider; READY is the child's only line of its own.
        assertThat(killed.log()).containsOnlyOnce(AgentMain.READY + " " + killed.workerId());
        assertThat(restarted.log()).containsOnlyOnce(AgentMain.READY + " " + restarted.workerId());

        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue());
        CheckResult found = Checks.run(e, checks.withCrashes(e.crashes()));
        assertThat(found.all()).as("%s run %d", s.id(), run).isEmpty();
        System.out.println(s.id() + " run " + run + " (" + Scenarios.killPoint(s, run) + "): " + intent.state()
                + " in " + ms + " ms, " + e.orders().size() + " order(s), killed " + killed.workerId() + " exit "
                + killed.exitValue() + ", restarted " + restarted.workerId() + " exit " + restarted.exitValue());
        return e;
    }

    private static List<LogEntry> completes(RunEvidence e) {
        return e.proxyLog().stream().filter(l -> l.method().equals("POST") && COMPLETE.matches(l.path())).toList();
    }

    // Covers: S9, NFR4, I1, I4, I5, 7.6 (kill -9 at K1, K2 and K3 in turn: the new worker takes over within one lease
    // and one period, reconciles, and the intent ends COMPLETED with exactly one order and one complete on the wire)
    @Test
    void s9_onceAtEachKillPoint_restartedWorkerCompletesWithOneOrder() {
        for (int run = 0; run < 3; run++) {
            RunEvidence e = runKilled(Scenarios.S9, run);

            assertThat(e.intents().getFirst().intent().state()).isEqualTo(IntentState.COMPLETED);
            assertThat(e.orders()).as("run %d", run).hasSize(1);
            assertThat(completes(e)).as("run %d: never resent, the merchant had it", run).singleElement()
                    .satisfies(l -> assertThat(l.ruleId()).isPresent());
        }
    }

    // Covers: S28, I1, I4, I5, 7.5 (killed late in RECONCILING with every read refused: the takeover comes after the
    // deadline, so the new worker escalates; the dropped complete made the one order, and nothing was resent)
    @Test
    void s28_lateKill_restartedWorkerEscalates() {
        RunEvidence e = runKilled(Scenarios.S28, 1);

        assertThat(e.intents().getFirst().intent().state()).isEqualTo(IntentState.ESCALATED);
        assertThat(e.orders()).as("odd run: the merchant processed the dropped complete").hasSize(1);
        assertThat(completes(e)).singleElement().satisfies(l -> assertThat(l.forwards()).isEqualTo(1));
    }
}
