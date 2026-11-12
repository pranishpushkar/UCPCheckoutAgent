package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * S1, S2, S3 and S13 (13.2, m4-plan section 6), each three times in one lane at time scale 4: the expected terminal
 * state, every check of m4-plan section 5 clean, one order, and on the wire exactly the fault the row describes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaultScenariosTest {

    private static final PathPattern CREATE = new PathPattern(Scenarios.CREATE);
    private static final PathPattern COMPLETE = new PathPattern(Scenarios.COMPLETE);
    private static final int RUNS = 3;

    private Lane lane;
    private RunScript script;
    private CheckContext checks;

    @BeforeAll
    void startLane(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        lane = Lane.start(0, key, dir);
        script = new RunScript(lane, TimeScale.of(4));
        checks = CheckContext.of(TimeScale.of(4), key, Scenarios.SHOP, RunScript.POLL);
    }

    @AfterAll
    void stopLane() {
        lane.close();
    }

    private static List<LogEntry> posts(RunEvidence e, PathPattern path) {
        return e.proxyLog().stream().filter(l -> l.method().equals("POST") && path.matches(l.path())).toList();
    }

    /** Runs {@code s} once and asserts what every row shares: no abort, COMPLETED, one order, every check clean. */
    private RunEvidence runClean(Scenario s, int run) {
        RunEvidence e = script.run(s, run, Scenarios.seedIndex(s) * 10_000L + run);
        assertThat(e.aborted()).as("%s run %d", s.id(), run).isEmpty();
        assertThat(e.intents()).hasSize(1);
        PurchaseIntent intent = e.intents().getFirst().intent();
        assertThat(intent.state()).as("%s run %d", s.id(), run).isIn(s.expected());
        assertThat(e.orders()).as("%s run %d orders", s.id(), run).hasSize(1);
        assertThat(e.orders().getFirst().checkoutId()).isEqualTo(intent.sessionId().orElseThrow().value());
        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue());
        assertThat(Checks.run(e, checks).all()).as("%s run %d", s.id(), run).isEmpty();
        return e;
    }

    // Covers: S1, I1, I4, 8.1, 8.2 (the first two creates closed before forwarding: Uncertain, retried under the same
    // key and body; the third reaches the merchant; COMPLETED with one order)
    @Test
    void s1_threeRuns_createRetriedUnderOneKeyAfterTwoClosedConnections() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S1, run);

            List<LogEntry> creates = posts(e, CREATE);
            assertThat(creates).as("run %d", run).hasSize(3);
            assertThat(creates).extracting(LogEntry::forwards).containsExactly(0, 0, 1);
            assertThat(creates.subList(0, 2)).allSatisfy(l -> assertThat(l.ruleId()).contains("close-create"));
            assertThat(creates).extracting(l -> l.idempotencyKey().orElseThrow()).containsOnly(
                    creates.getFirst().idempotencyKey().orElseThrow());
            assertThat(creates).extracting(l -> l.bodyHmac().orElseThrow()).containsOnly(
                    creates.getFirst().bodyHmac().orElseThrow());
            assertThat(posts(e, COMPLETE)).hasSize(1);
        }
    }

    // Covers: S2, I1, I4, I7, 5.4 (the merchant completes, the answer is dropped; reconciliation reads the session as
    // completed; one complete on the wire, one order)
    @Test
    void s2_threeRuns_droppedCompleteIsReconciledWithOneOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S2, run);

            List<LogEntry> completes = posts(e, COMPLETE);
            assertThat(completes).as("run %d", run).singleElement().satisfies(l -> {
                assertThat(l.forwards()).isEqualTo(1);
                assertThat(l.merchantStatus()).isEqualTo(OptionalInt.of(200));
                assertThat(l.action()).contains("forward_then_drop_response");
            });
            String session = e.intents().getFirst().intent().sessionId().orElseThrow().value();
            assertThat(e.proxyLog()).as("run %d: a reconciliation read after the drop", run)
                    .anySatisfy(l -> {
                        assertThat(l.method()).isEqualTo("GET");
                        assertThat(l.path()).isEqualTo(Scenarios.CREATE + "/" + session);
                        assertThat(l.seq()).isGreaterThan(completes.getFirst().seq());
                    });
            assertThat(posts(e, CREATE)).hasSize(1);
        }
    }

    // Covers: S3, I1, I7, 7.5 (the answer is held past the complete timeout; the merchant had committed, so the read
    // shows completed and nothing is resent; one complete, one order)
    @Test
    void s3_threeRuns_heldCompleteTimesOutAndIsNotResent() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S3, run);

            assertThat(posts(e, COMPLETE)).as("run %d", run).singleElement().satisfies(l -> {
                assertThat(l.action()).contains("forward_then_delay_response");
                assertThat(l.merchantStatus()).isEqualTo(OptionalInt.of(200));
            });
            assertThat(e.intents().getFirst().intent().uncertainSince()).as("run %d: the client timed out", run)
                    .isPresent();
        }
    }

    // Covers: S13, I1, 7.5 (both copies of the first complete reach the merchant, together in even runs and one after
    // the other in odd runs; one log entry with two forwards, one order)
    @Test
    void s13_threeRuns_duplicatedCompleteMakesOneOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S13, run);

            assertThat(posts(e, COMPLETE)).as("run %d", run).singleElement().satisfies(l -> {
                assertThat(l.forwards()).isEqualTo(2);
                assertThat(l.action()).contains("duplicate_request");
                assertThat(l.upstreamError()).isFalse();
            });
            assertThat(e.intents().getFirst().intent().state()).isEqualTo(IntentState.COMPLETED);
            assertThat(e.intents().getFirst().intent().orderId()).isEqualTo(Optional.of(e.orders().getFirst().id()));
        }
    }
}
