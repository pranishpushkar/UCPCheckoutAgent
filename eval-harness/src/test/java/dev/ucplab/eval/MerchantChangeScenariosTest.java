package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.check.CheckContext;
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
 * S5a, S5b, S6 and S7 (13.2, m4-plan section 6; S5 as two rows, Decision 3), each three times in one lane at time
 * scale 4: the expected terminal state, every check of m4-plan section 5 clean, and the orders, approvals and wire the
 * row describes. The changes at the merchant go through its lab controls; nothing in merchant/src changes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantChangeScenariosTest {

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

    private static String completeKey(RunEvidence e, int revision) {
        return IdempotencyKey.derive(intent(e).id(), MutatingOp.COMPLETE, revision).value();
    }

    private static String sessionStatus(RunEvidence e) {
        return e.sessions().get(intent(e).sessionId().orElseThrow().value()).status();
    }

    // Covers: S5, I2, I3, I7, P7 (the price rises between quote and complete; complete rev 0 is answered
    // price_changed, the user approves the re-quote, complete rev 1 makes the one order at the new, approved total)
    @Test
    void s5a_threeRuns_reapprovedRequoteCompletesAtTheNewTotal() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S5A, run);

            assertThat(e.approvals()).as("run %d", run).hasSize(2).allSatisfy(d -> assertThat(d.approved()).isTrue());
            // Totals include the merchant's tax, so the test compares them with each other, not with a constant.
            ApprovalSnapshot first = e.approvals().get(0).snapshot();
            ApprovalSnapshot second = e.approvals().get(1).snapshot();
            assertThat(first.lines()).extracting(SnapshotLine::unitPriceMinor).containsExactly(2500L);
            assertThat(second.lines()).extracting(SnapshotLine::unitPriceMinor).containsExactly(Scenarios.S5_NEW_PRICE);
            assertThat(second.totalMinor()).isGreaterThan(first.totalMinor());
            assertThat(completes(e)).extracting(l -> l.idempotencyKey().orElseThrow())
                    .containsExactly(completeKey(e, 0), completeKey(e, 1));
            assertThat(e.orders()).singleElement()
                    .satisfies(o -> assertThat(o.amountMinor()).isEqualTo(second.totalMinor()));
            assertThat(e.sessions().get(e.orders().getFirst().checkoutId()).lines())
                    .extracting(GroundTruth.SessionLine::unitPriceMinor).containsExactly(Scenarios.S5_NEW_PRICE);
            assertThat(intent(e).requoteCount()).isEqualTo(1);
        }
    }

    // Covers: S5, I2, I3 (the user declines the re-quote: REJECTED, no order, and no complete under the new revision)
    @Test
    void s5b_threeRuns_declinedRequoteIsRejectedWithNoOrder() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S5B, run);

            assertThat(e.approvals()).as("run %d", run).extracting(AutoApprover.Decision::approved)
                    .containsExactly(true, false);
            assertThat(completes(e)).extracting(l -> l.idempotencyKey().orElseThrow())
                    .containsExactly(completeKey(e, 0));
            assertThat(e.orders()).isEmpty();
            assertThat(sessionStatus(e)).as("run %d: the best-effort cancel reached the merchant", run)
                    .isEqualTo("canceled");
        }
    }

    // Covers: S6, I1 (the item sells out after the quote: FAILED_NOT_CHARGED, no order, the session canceled once the
    // cancel it owed is sent)
    @Test
    void s6_threeRuns_soldOutAfterTheQuoteFailsNotCharged() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S6, run);

            assertThat(e.orders()).as("run %d", run).isEmpty();
            assertThat(intent(e).failureReason()).hasValueSatisfying(r -> assertThat(r).contains("out_of_stock"));
            assertThat(sessionStatus(e)).isEqualTo("canceled");
        }
    }

    // Covers: S7, I1 (the merchant declines the card: FAILED_NOT_CHARGED, no order, one complete on the wire)
    @Test
    void s7_threeRuns_declinedPaymentFailsNotCharged() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S7, run);

            assertThat(e.orders()).as("run %d", run).isEmpty();
            assertThat(completes(e)).hasSize(1);
            assertThat(intent(e).failureReason()).hasValueSatisfying(r -> assertThat(r).contains("payment_declined"));
            assertThat(intent(e).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        }
    }
}
