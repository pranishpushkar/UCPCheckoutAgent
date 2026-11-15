package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.CheckResult;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.eval.report.RunResult;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.ucp.CircuitBreaker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rows over several merchants (m6a-plan C12 to C14), a few runs each in one lane of three at time scale 4. Every run
 * must be unaborted, expected by its row, and clean under every check, run per merchant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiMerchantScenariosTest {

    private static final int RUNS = 3;
    private static final TimeScale SCALE = TimeScale.of(4);
    private static final MerchantId A = Scenarios.SHOP;
    private static final MerchantId B = Scenarios.SHOP_B;
    private static final MerchantId C = Scenarios.SHOP_C;

    private Lane lane;
    private RunScript script;
    private CheckContext checks;

    @BeforeAll
    void startLane(@TempDir Path dir) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        lane = Lane.start(0, key, dir, 3);
        script = new RunScript(lane, SCALE);
        checks = CheckContext.of(SCALE, key, Scenarios.SHOP, RunScript.POLL);
    }

    @AfterAll
    void stopLane() {
        lane.close();
    }

    private RunEvidence run(Scenario s, int run) {
        return script.run(s, run, Scenarios.seedIndex(s) * 10_000L + run);
    }

    /** Runs {@code s} once: not aborted, expected by the row, every check clean, every proxy settled. */
    private RunEvidence runClean(Scenario s, int run) {
        RunEvidence e = run(s, run);
        assertThat(e.aborted()).as("%s run %d", s.id(), run).isEmpty();
        CheckResult found = Checks.run(e, checks);
        assertThat(found.all()).as("%s run %d", s.id(), run).isEmpty();
        assertThat(RunResult.of(s, e, found).expected()).as("%s run %d expected", s.id(), run).isTrue();
        assertThat(e.views()).as("%s run %d has a view of each merchant", s.id(), run)
                .extracting(RunEvidence.MerchantView::merchant)
                .containsExactlyElementsOf(List.of(A, B, C).subList(0, s.multi().merchants()));
        e.views().forEach(v -> assertThat(v.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue()));
        return e;
    }

    private static RunEvidence.MerchantView view(RunEvidence e, MerchantId merchant) {
        return e.views().stream().filter(v -> v.merchant().equals(merchant)).findFirst().orElseThrow();
    }

    // Covers: S20, 19, 8.3, I1, I11, 6.2 (merchant B's catalog hangs once, then answers 503. The search returns near the
    // per-merchant limit, not after B's retries; it names B in `unavailable` and holds A's and C's items; a purchase at A
    // is approved and completes with one order, none at B or C; B's breaker is OPEN and A's and C's are CLOSED)
    @Test
    void aS20_threeRuns_theFailingMerchantIsLeftOut_theOthersCarryOn() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.A_S20, run);

            assertThat(e.agentRun().orElseThrow().record().toolEvents()).extracting(t -> t.tool())
                    .containsExactly("search_catalog", "search_catalog", "search_catalog", "propose_purchase");
            Duration first = e.agentRun().get().record().toolEvents().getFirst().took();
            assertThat(first).as("the first search, with B hung").isLessThan(RunScript.searchLimit(SCALE).multipliedBy(2));
            assertThat(e.intents()).singleElement().satisfies(p -> {
                assertThat(p.intent().state()).isEqualTo(IntentState.COMPLETED);
                assertThat(p.intent().merchantId()).isEqualTo(A);
            });
            assertThat(view(e, A).orders()).hasSize(1);
            assertThat(view(e, B).orders()).isEmpty();
            assertThat(view(e, C).orders()).isEmpty();
            assertThat(view(e, A).breaker()).contains(CircuitBreaker.State.CLOSED);
            assertThat(view(e, B).breaker()).contains(CircuitBreaker.State.OPEN);
            assertThat(view(e, C).breaker()).contains(CircuitBreaker.State.CLOSED);
            List<LogEntry> atB = view(e, B).proxyLog();
            assertThat(atB).extracting(LogEntry::path).noneMatch(p -> p.contains("/checkout-sessions"));
            assertThat(view(e, A).proxyLog()).extracting(LogEntry::path).anyMatch(p -> p.contains("/checkout-sessions"));
        }
    }

    /** What S21 must leave: one order at B, none at A, one intent COMPLETED, the other out of stock. */
    private static void assertOneWinner(RunEvidence e, String what) {
        assertThat(e.intents()).as(what).hasSize(2);
        assertThat(e.intents()).extracting(p -> p.intent().state())
                .containsExactlyInAnyOrder(IntentState.COMPLETED, IntentState.FAILED_NOT_CHARGED);
        assertThat(e.intents()).filteredOn(p -> p.intent().state() == IntentState.FAILED_NOT_CHARGED)
                .singleElement().satisfies(p -> assertThat(p.intent().failureReason()).contains("out_of_stock"));
        assertThat(e.intents()).extracting(p -> p.intent().owner()).doesNotHaveDuplicates();
        assertThat(e.intents()).extracting(p -> p.intent().merchantId()).containsOnly(B);
        assertThat(view(e, B).orders()).as(what + ": orders at B").hasSize(1);
        assertThat(view(e, A).orders()).as(what + ": orders at A").isEmpty();
    }

    // Covers: S21, I1, 19, A.1 (two users buy the one Wool Cap at B at the same moment. Both proposals reach approval at
    // stock 1, both are approved, both completes are in flight together; B serves one and answers out_of_stock to the
    // other: exactly one order, one intent COMPLETED, the other FAILED_NOT_CHARGED with out_of_stock)
    @Test
    void s21_runs_exactlyOneOrder_oneWinner_oneOutOfStock() {
        for (int run = 0; run < RUNS; run++) {
            RunEvidence e = runClean(Scenarios.S21, run);

            assertOneWinner(e, "S21 run " + run);
        }
    }

    /** S21 with the merchant's stock raised to 2 once both quotes are pending: a merchant whose stock check never trips. */
    private static Scenario s21AgainstAMerchantThatOversells() {
        Scenario s = Scenarios.S21;
        return new Scenario(s.id(), s.title(), s.fault(), s.expected(), s.requiredInvariants(), s.rules(), s.approver(),
                s.driver(), s.target(), s.basket(), s.paymentToken(), (m, t) -> m.setStock("b_cap_03", 2),
                s.faultWindow(), s.agent(), s.multi());
    }

    // Covers: S21, I1 (the row can fail: against a merchant that sells the Wool Cap twice, two orders exist for the one in
    // stock, and the run is aborted with the oversell, never passed. The lab merchant cannot be stubbed, so its own stock
    // control stands in for a merchant that skips the check)
    @Test
    void s21_failsAgainstAMerchantThatOversells() {
        RunEvidence e = run(s21AgainstAMerchantThatOversells(), 0);

        assertThat(e.aborted()).hasValueSatisfying(why -> assertThat(why).contains("S21 claim").contains("2 orders"));
        assertThat(view(e, B).orders()).hasSize(2);
    }
}
