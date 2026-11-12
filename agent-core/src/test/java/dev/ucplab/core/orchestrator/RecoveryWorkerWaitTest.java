package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.QUOTING;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.T0;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.InMemoryRecoveryScan;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** RecoveryWorker (7.6): it never sleeps past its lease; a long wait is left to a later pass. */
class RecoveryWorkerWaitTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final Lab lab = new Lab();
    private final List<Duration> sleeps = new ArrayList<>();

    /** Answers every advance with a not-idle step due at a scripted time, and counts the calls. */
    private final class Scripted implements Orchestrator {
        int advances;
        final List<IntentId> advanced = new ArrayList<>();
        final java.util.Set<IntentId> finishAtOnce = new java.util.HashSet<>();
        Optional<Instant> next = Optional.empty();
        boolean idle;
        int idleAfter = Integer.MAX_VALUE;

        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            advances++;
            advanced.add(id);
            boolean done = idle || advances > idleAfter || finishAtOnce.contains(id);
            return new StepResult(id, RECONCILING, RECONCILING, done, done ? Optional.empty() : next);
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    }

    private RecoveryWorker worker(Orchestrator orchestrator, Duration lease) {
        return new RecoveryWorker(lab.intents, lab.scan, orchestrator,
                lab.clock, d -> {
                    sleeps.add(d);
                    lab.clock.advance(d);
                }, Runnable::run, new RecoveryConfig(Duration.ofSeconds(5), lease, 10, 500), "worker-r");
    }

    private IntentId reconcilingIntent() {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId("r-1"), ALICE, SHOP, "dedupe-r",
                List.of(new ProposedLine("item_123", 1)), "alice@example.com", T0).withState(RECONCILING, T0);
        lab.intents.insert(intent);
        return intent.id();
    }

    // Covers: 7.5, 7.6 (a wait longer than half the lease is not slept: the driver returns at once, the clock has not
    // moved, and the thread is free)
    @Test
    void runOnce_doesNotSleep_whenTheWaitExceedsHalfTheLease() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(20));
        IntentId id = reconcilingIntent();

        assertThat(worker(orchestrator, LEASE).runOnce()).containsExactly(id);

        assertThat(sleeps).isEmpty();
        assertThat(lab.clock.instant()).isEqualTo(T0);
        assertThat(orchestrator.advances).isEqualTo(1);
    }

    // Covers: 7.6 (a wait of up to half the lease is still slept: only the long ones are handed back)
    @Test
    void runOnce_sleeps_whenTheWaitIsWithinHalfTheLease() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(15));
        orchestrator.idleAfter = 1;
        reconcilingIntent();

        worker(orchestrator, LEASE).runOnce();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(15));
        assertThat(orchestrator.advances).isEqualTo(2);
    }

    // Covers: 7.6 (a pass that comes before the intent is due leaves it alone; a pass at nextAttemptAt advances it,
    // although this same worker still holds the lease: claimRecoverable takes back its own live lease)
    @Test
    void laterPass_advancesTheIntent_onlyOnceItIsDue_underTheSameWorkersLease() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(20));
        IntentId id = reconcilingIntent();
        RecoveryWorker worker = worker(orchestrator, LEASE);
        worker.runOnce();
        assertThat(orchestrator.advances).isEqualTo(1);
        assertThat(lab.intent(id).leaseOwner()).contains("worker-r");

        lab.clock.advance(Duration.ofSeconds(5));
        assertThat(worker.runOnce()).as("not due yet: no early read").isEmpty();
        assertThat(orchestrator.advances).isEqualTo(1);

        lab.clock.advance(Duration.ofSeconds(15));
        assertThat(lab.intent(id).leaseUntil()).hasValueSatisfying(t -> assertThat(t).isAfter(lab.clock.instant()));
        orchestrator.idle = true;
        assertThat(worker.runOnce()).containsExactly(id);
        assertThat(orchestrator.advances).isEqualTo(2);
    }

    // Covers: 7.6, 7.8 (the lease is kept alive while the intent waits: every pass renews it, so a long wait does not
    // let it lapse to another worker)
    @Test
    void waitingIntent_keepsItsLease_acrossPasses() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(60));
        IntentId id = reconcilingIntent();
        RecoveryWorker worker = worker(orchestrator, LEASE);

        for (int i = 0; i < 11; i++) {
            worker.runOnce();
            lab.clock.advance(Duration.ofSeconds(5));
        }

        assertThat(lab.intent(id).leaseOwner()).contains("worker-r");
        assertThat(lab.intent(id).leaseUntil()).hasValueSatisfying(t -> assertThat(t).isAfter(lab.clock.instant()));
        assertThat(orchestrator.advances).isEqualTo(1);
    }

    // Covers: 7.5, 7.6, I1, I7 (a real reconciliation whose polls are longer than half a short lease: no sleep, later
    // passes finish it, and the merchant makes exactly one order)
    @Test
    void realReconciliation_finishesAcrossPasses_withOneOrder() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        assertThat(a.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker w = lab.worker("worker-r");
        Duration shortLease = Duration.ofMillis(200);
        // The worker never reads the period (only RecoveryLoop does); it is 100 ms only to satisfy the 7.6 rule.
        RecoveryWorker worker = new RecoveryWorker(lab.intents, lab.scan,
                w.orchestrator(), lab.clock, d -> {
                    sleeps.add(d);
                    lab.clock.advance(d);
                }, Runnable::run, new RecoveryConfig(Duration.ofMillis(100), shortLease, 10, 500), "worker-r");

        for (int pass = 0; pass < 40 && lab.intent(id).state() != COMPLETED; pass++) {
            worker.runOnce();
            lab.clock.advance(Duration.ofSeconds(3));
        }

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(sleeps).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(shortLease.dividedBy(2)));
    }

    private IntentId insertReconciling(String id, Instant createdAt) {
        lab.intents.insert(PurchaseIntent.proposed(new IntentId(id), ALICE, SHOP, "dedupe-" + id,
                List.of(new ProposedLine("item_123", 1)), "alice@example.com", createdAt).withState(RECONCILING, createdAt));
        return new IntentId(id);
    }

    // Covers: 7.6 (parked intents that are not due cannot fill the pass limit: with more parked intents than the limit,
    // a new recoverable intent is still advanced in the pass it appears in)
    @Test
    void parkedIntents_doNotStarveANewRecoverableIntent() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(25));
        RecoveryWorker worker = new RecoveryWorker(lab.intents, lab.scan,
                orchestrator, lab.clock, d -> {
                    sleeps.add(d);
                    lab.clock.advance(d);
                }, Runnable::run, new RecoveryConfig(Duration.ofSeconds(5), LEASE, 2, 500), "worker-r");
        IntentId r1 = insertReconciling("r-1", T0);
        IntentId r2 = insertReconciling("r-2", T0.plusMillis(1));
        IntentId r3 = insertReconciling("r-3", T0.plusMillis(2));
        worker.runOnce();
        lab.clock.advance(Duration.ofSeconds(1));
        worker.runOnce();
        assertThat(orchestrator.advanced).as("three parked, limit two").containsExactly(r1, r2, r3);

        IntentId fresh = insertReconciling("r-4", lab.clock.instant());
        orchestrator.finishAtOnce.add(fresh);
        lab.clock.advance(Duration.ofSeconds(1));
        worker.runOnce();

        assertThat(orchestrator.advanced).as("the new intent went through in its own pass")
                .containsExactly(r1, r2, r3, fresh);
        assertThat(sleeps).isEmpty();
    }

    // Covers: 7.6 (a parked intent keeps its lease while it is left out of the claim)
    @Test
    void parkedIntents_stillHaveTheirLeaseRenewed_whileExcludedFromTheClaim() {
        Scripted orchestrator = new Scripted();
        orchestrator.next = Optional.of(T0.plusSeconds(100));
        IntentId id = insertReconciling("r-1", T0);
        RecoveryWorker worker = worker(orchestrator, LEASE);
        worker.runOnce();

        for (int i = 0; i < 12; i++) {
            lab.clock.advance(Duration.ofSeconds(5));
            worker.runOnce();
        }

        assertThat(lab.intent(id).leaseOwner()).contains("worker-r");
        assertThat(lab.intent(id).leaseUntil()).hasValueSatisfying(t -> assertThat(t).isAfter(lab.clock.instant()));
        assertThat(orchestrator.advances).isEqualTo(1);
    }
}
