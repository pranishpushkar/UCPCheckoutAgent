package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.eval.check.CheckContext;
import dev.ucplab.eval.check.Checks;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.PathPattern;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * S0, S4 and S11 driven end to end in one lane at time scale 4 (m4-plan section 2): the real orchestrator, SQLite,
 * gateway, proxy and merchant, on real time. Each run's evidence must hold O, G, L, A and D, and the outcome must come
 * from the merchant's ground truth.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunScriptTest {

    private static final PathPattern UCP = new PathPattern("/ucp/v1/**");
    private static final PathPattern CREATE = new PathPattern("/ucp/v1/checkout-sessions");
    private static final PathPattern UNDER_SESSIONS = new PathPattern("/ucp/v1/checkout-sessions/**");
    private static final PathPattern COMPLETE = new PathPattern("/ucp/v1/checkout-sessions/*/complete");

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

    private static List<LogEntry> requests(RunEvidence e, String method, PathPattern path) {
        return e.proxyLog().stream().filter(l -> l.method().equals(method) && path.matches(l.path())).toList();
    }

    private static PurchaseIntent onlyIntent(RunEvidence e) {
        assertThat(e.intents()).hasSize(1);
        return e.intents().getFirst().intent();
    }

    // Covers: S0, FR2-FR5, 13.1, I1, I2, I3, I4, I5, I7 (no fault: COMPLETED with one order, read from the merchant;
    // the evidence holds O, G, L, A and D, and the real run's evidence passes every check of m4-plan section 5)
    @Test
    void s0_threeRuns_completeWithOneOrderEach() {
        for (int run = 0; run < 3; run++) {
            assertCleanS0(script.run(Scenarios.S0, run, 1000 + run), run);
        }
    }

    // Covers: S0, S8, I1 (m4-plan risk 4: S0, then a run where every UCP request is answered 503, then S0 again in the
    // same lane; nothing from the outage run - breaker, pooled connections, rules, late requests - reaches the last)
    @Test
    void s0AfterAnOutageRun_isClean() {
        Scenario outage = new Scenario("S8x", "every UCP request answered 503", "fail_before_forward 503 on /ucp/v1/**",
                Set.of(IntentState.FAILED_NOT_CHARGED, IntentState.ESCALATED), List.of("I1"),
                t -> new RuleSet(1, List.of(new Rule("outage", new Match(Optional.empty(),
                        Optional.of(new PathPattern("/ucp/v1/**")), OptionalInt.empty(), OptionalDouble.empty()),
                        Action.FailBeforeForward.status(503)))),
                AutoApprover.Policy.APPROVE_ALL, Scenario.Driver.PROPOSE_ONCE, Scenarios.SHOP, Scenarios.TWO_SHIRTS,
                Scenarios.PAY_OK);

        assertCleanS0(script.run(Scenarios.S0, 0, 4000), 0);
        RunEvidence down = script.run(outage, 1, 4001);
        assertThat(down.aborted()).isEmpty();
        assertThat(down.orders()).isEmpty();
        assertThat(onlyIntent(down).state()).isIn(outage.expected());
        // The profile fetch (/.well-known/ucp) is outside the pattern; every UCP request was refused.
        assertThat(down.proxyLog()).filteredOn(l -> UCP.matches(l.path())).isNotEmpty()
                .allSatisfy(l -> assertThat(l.forwarded()).isFalse());
        assertPassesEveryCheck(down);
        assertCleanS0(script.run(Scenarios.S0, 2, 4002), 2);
    }

    private void assertCleanS0(RunEvidence e, int run) {
        assertThat(e.aborted()).as("run %d", run).isEmpty();
        PurchaseIntent intent = onlyIntent(e);
        assertThat(intent.state()).isEqualTo(IntentState.COMPLETED);
        String session = intent.sessionId().orElseThrow().value();
        // O and G: one order, for this run's session, which the merchant itself shows as completed with it.
        assertThat(e.orders()).hasSize(1);
        assertThat(e.orders().getFirst().checkoutId()).isEqualTo(session);
        assertThat(e.sessions().get(session).status()).isEqualTo("completed");
        assertThat(e.sessions().get(session).orderId()).contains(e.orders().getFirst().id());
        assertThat(e.sessions().get(session).lines()).extracting(GroundTruth.SessionLine::itemId,
                GroundTruth.SessionLine::quantity).containsExactly(org.assertj.core.groups.Tuple.tuple("item_123", 2L));
        // L: one create and one complete, both settled, and nothing else under the sessions path but this run's.
        assertThat(requests(e, "POST", CREATE)).hasSize(1);
        assertThat(requests(e, "POST", COMPLETE)).hasSize(1)
                .allSatisfy(l -> assertThat(l.bodyHmac()).isPresent());
        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.settled()).isTrue());
        assertThat(e.proxyLog()).allSatisfy(l -> assertThat(l.action()).isEmpty());
        // A: one approval, of this run's session, by the approver.
        assertThat(e.approvals()).hasSize(1);
        assertThat(e.approvals().getFirst().approved()).isTrue();
        assertThat(e.approvals().getFirst().snapshot().sessionId().value()).isEqualTo(session);
        // D: the attempts, events and approval row.
        assertThat(e.intents().getFirst().attempts()).isNotEmpty();
        assertThat(e.intents().getFirst().events()).isNotEmpty();
        assertThat(e.intents().getFirst().approvalRows()).hasSize(1);
        assertThat(e.timeToTerminal()).isPresent();
        assertPassesEveryCheck(e);
    }

    /** Every check of m4-plan section 5 on the run's own evidence. */
    private void assertPassesEveryCheck(RunEvidence e) {
        assertThat(Checks.run(e, checks).all()).as("%s run %d", e.scenarioId(), e.run()).isEmpty();
    }

    // Covers: S4, I1, FR3 (the same proposal twice, one after the other and then concurrently: one intent, one create
    // key on the wire, one order)
    @Test
    void s4_threeRuns_oneIntentOneOrder() {
        for (int run = 0; run < 3; run++) {
            RunEvidence e = script.run(Scenarios.S4, run, 2000 + run);

            assertThat(e.aborted()).as("run %d", run).isEmpty();
            assertThat(e.proposed()).hasSize(2);
            assertThat(e.proposed().get(0)).as("run %d: both calls return the same intent", run)
                    .isEqualTo(e.proposed().get(1));
            assertThat(onlyIntent(e).state()).isEqualTo(IntentState.COMPLETED);
            assertThat(e.orders()).hasSize(1);
            assertThat(requests(e, "POST", CREATE)).extracting(l -> l.idempotencyKey().orElseThrow()).hasSize(1);
            assertPassesEveryCheck(e);
        }
    }

    // Covers: S11, P1, I3, FR7 (a merchant missing from the registry: REJECTED by P1 before anything reaches it)
    @Test
    void s11_oneRun_rejectedWithNothingOnTheWire() {
        RunEvidence e = script.run(Scenarios.S11, 0, 3000);

        assertThat(e.aborted()).isEmpty();
        PurchaseIntent intent = onlyIntent(e);
        assertThat(intent.state()).isEqualTo(IntentState.REJECTED);
        assertThat(intent.failureReason()).hasValueSatisfying(r -> assertThat(r).contains("P1"));
        assertThat(requests(e, "POST", CREATE)).isEmpty();
        assertThat(e.proxyLog()).noneMatch(l -> UNDER_SESSIONS.matches(l.path()) || CREATE.matches(l.path()));
        assertThat(e.orders()).isEmpty();
        assertThat(e.approvals()).isEmpty();
        assertPassesEveryCheck(e);
    }
}
