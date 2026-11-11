package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.QUOTING;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.intent.IntentState.REJECTED;
import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.T0;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.InMemoryRecoveryScan;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** RecoveryWorker (7.6): what a pass picks up, how it drives, and what it never does. */
class RecoveryWorkerTest {

    private final Lab lab = new Lab();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Tracker tracker = new Tracker();

    /** Counts {@code advance} calls per intent and the most that ever ran at once for one intent, across workers. */
    private static final class Tracker {
        final Map<IntentId, AtomicInteger> advances = new ConcurrentHashMap<>();
        final Map<IntentId, AtomicInteger> active = new ConcurrentHashMap<>();
        final Map<IntentId, AtomicInteger> maxActive = new ConcurrentHashMap<>();

        int advances(IntentId id) {
            return advances.getOrDefault(id, new AtomicInteger()).get();
        }

        int maxActive(IntentId id) {
            return maxActive.getOrDefault(id, new AtomicInteger()).get();
        }
    }

    private record Counting(Orchestrator delegate, Tracker tracker) implements Orchestrator {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String rationale) {
            return delegate.propose(auth, merchant, lines, rationale);
        }

        @Override
        public StepResult advance(IntentId id) {
            tracker.advances.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            int now = tracker.active.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            tracker.maxActive.computeIfAbsent(id, k -> new AtomicInteger()).accumulateAndGet(now, Math::max);
            try {
                return delegate.advance(id);
            } finally {
                tracker.active.get(id).decrementAndGet();
            }
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            return delegate.status(auth, id);
        }
    }

    private RecoveryWorker recovery(String id) {
        return recovery(id, lab.merchant, Runnable::run, RecoveryConfig.defaults());
    }

    private RecoveryWorker recovery(
            String id, MerchantGateway gateway, java.util.concurrent.Executor executor, RecoveryConfig config) {
        return recovery(id, gateway, executor, config, tracker);
    }

    private RecoveryWorker recovery(
            String id, MerchantGateway gateway, java.util.concurrent.Executor executor, RecoveryConfig config,
            Tracker own) {
        Lab.Worker w = lab.worker(id, gateway);
        return new RecoveryWorker(lab.intents, new InMemoryRecoveryScan(lab.intents, lab.approvals),
                new Counting(w.orchestrator(), own), lab.clock, d -> {
                    sleeps.add(d);
                    lab.clock.advance(d);
                }, executor, config, id);
    }

    private void lapseLease() {
        lab.clock.advance(lab.config.lease().plusSeconds(1));
    }

    /** An intent inserted in QUOTING as a crash would leave it before the create attempt was written. */
    private IntentId quotingIntent(String n, java.time.Instant createdAt) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId("q-" + n), ALICE, SHOP, "dedupe-" + n,
                List.of(new ProposedLine("item_123", 1)), "alice@example.com", createdAt)
                .withState(QUOTING, createdAt);
        lab.intents.insert(intent);
        return intent.id();
    }

    // Covers: 7.6 (QUOTING is picked up and advanced: the create is sent and the quote reaches the approval)
    @Test
    void runOnce_advancesQuotingIntent() {
        IntentId id = quotingIntent("0", T0);

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).state()).isEqualTo(AWAITING_APPROVAL);
    }

    // Covers: 7.6 (APPROVED is picked up: a crash between approval and the first complete does not strand the intent)
    @Test
    void runOnce_advancesApprovedIntent() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        lapseLease();

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6, I1, I7 (COMPLETING with an attempt left SENT is reconciled, never resent blind; the worker waits
    // through the Sleeper and the one order is made)
    @Test
    void runOnce_advancesCompletingIntent_throughReconciliationAndSleeper() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashBeforeNext(MerchantCall.COMPLETE);
        Lab.Worker dying = lab.worker("worker-b", gateway);
        lapseLease();
        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.intent(id).state()).isEqualTo(COMPLETING);
        lapseLease();

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(sleeps).as("the worker, not advance, waited for the poll and the quiet period").isNotEmpty();
    }

    // Covers: 7.6, 7.5 (RECONCILING is picked up and read until the outcome is known)
    @Test
    void runOnce_advancesReconcilingIntent() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        assertThat(a.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lapseLease();

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6, I5 (AWAITING_APPROVAL past expires_at is found by the scan, not claimed, and moved to REJECTED, and
    // its session is cancelled)
    @Test
    void runOnce_expiresApprovalFoundByScan() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).state()).isEqualTo(REJECTED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // Covers: 7.6 (an intent with cancel_pending set is picked up and its cancel sent)
    @Test
    void runOnce_sendsOwedCancel() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, false);
        assertThat(lab.intent(id).cancelPending()).isTrue();
        lapseLease();

        assertThat(recovery("worker-r").runOnce()).containsExactly(id);

        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intents.attempts(id)).filteredOn(x -> x.op() == AttemptOp.CANCEL).hasSize(1);
    }

    // Covers: 7.6 (an intent waiting for the owner, or with a live lease held by another worker, is left alone)
    @Test
    void runOnce_leavesIntentsAlone_whenNothingIsDue() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId waiting = lab.propose(a, TWO_SHIRTS).intentId();
        lapseLease();
        IntentId held = quotingIntent("5", lab.clock.instant());
        lab.intents.claim(held, "worker-a", lab.config.lease());

        assertThat(recovery("worker-r").runOnce()).isEmpty();

        assertThat(lab.intent(waiting).state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(tracker.advances(held)).isZero();
    }

    // Covers: 7.6 (oldest first, at most the limit)
    @Test
    void runOnce_picksOldestFirst_upToTheLimit() {
        IntentId third = quotingIntent("2", T0.plusSeconds(2));
        IntentId first = quotingIntent("0", T0);
        IntentId second = quotingIntent("1", T0.plusSeconds(1));
        RecoveryConfig two = new RecoveryConfig(Duration.ofSeconds(5), lab.config.lease(), 2, 500);

        assertThat(recovery("worker-r", lab.merchant, Runnable::run, two).runOnce()).containsExactly(first, second);

        assertThat(lab.intent(first).state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.intent(second).state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.intent(third).state()).isEqualTo(QUOTING);
    }

    // Covers: 7.6, 7.8 (a second worker's pass, run while the first is inside advance, does not touch the intent)
    @Test
    void runOnce_doesNotAdvanceIntentAnotherWorkerIsAdvancing() {
        IntentId id = quotingIntent("0", T0);
        Tracker secondsTracker = new Tracker();
        RecoveryWorker second = recovery("worker-b", lab.merchant, Runnable::run, RecoveryConfig.defaults(), secondsTracker);
        List<IntentId> secondPicked = new ArrayList<>();
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.duringNext(MerchantCall.CREATE, () -> secondPicked.addAll(second.runOnce()));

        assertThat(recovery("worker-a", gateway, Runnable::run, RecoveryConfig.defaults()).runOnce()).containsExactly(id);

        assertThat(secondPicked).isEmpty();
        assertThat(secondsTracker.advances(id)).as("the second worker never advanced it").isZero();
        assertThat(tracker.advances(id)).as("the first worker: the quote step, then idle").isEqualTo(2);
        assertThat(lab.intent(id).state()).isEqualTo(AWAITING_APPROVAL);
    }

    // Covers: 7.6, 7.8 (two workers on real threads over many intents: never two advances of one intent at once, and
    // every intent is advanced to the same place)
    @Test
    void runOnce_neverRunsTwoAdvancesOfOneIntentAtOnce_whenTwoWorkersRaceOnThreads() throws Exception {
        List<IntentId> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(quotingIntent(String.valueOf(i), T0.plusMillis(i)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            RecoveryWorker a = recovery("worker-a", lab.merchant, pool, RecoveryConfig.defaults());
            RecoveryWorker b = recovery("worker-b", lab.merchant, pool, RecoveryConfig.defaults());
            ExecutorService passes = Executors.newFixedThreadPool(2);
            try {
                passes.submit(a::runOnce).get(30, TimeUnit.SECONDS);
                passes.submit(b::runOnce).get(30, TimeUnit.SECONDS);
                List<java.util.concurrent.Future<?>> both = List.of(passes.submit(a::runOnce), passes.submit(b::runOnce));
                for (java.util.concurrent.Future<?> f : both) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                passes.shutdown();
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        for (IntentId id : ids) {
            assertThat(tracker.maxActive(id)).as("concurrent advances of %s", id).isEqualTo(1);
            assertThat(lab.intent(id).state()).isEqualTo(AWAITING_APPROVAL);
        }
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(12);
    }

    // Covers: 7.6 fencing, I1 (a paused zombie whose lease was taken mid-step cannot write: its resolve fails, the
    // worker drops the intent without another advance, and the intent is exactly as the new owner left it)
    @Test
    void runOnce_dropsIntent_whenLeaseWasTakenMidStep() {
        Lab.Worker setup = lab.worker("worker-setup");
        IntentId id = lab.propose(setup, TWO_SHIRTS).intentId();
        lab.decide(setup, id, true);
        lapseLease();
        RecoveryWorker newOwner = recovery("worker-new");
        long[] versionAfterTakeover = new long[1];
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.duringNext(MerchantCall.COMPLETE, () -> {
            lapseLease();
            newOwner.runOnce();
            versionAfterTakeover[0] = lab.intent(id).version();
        });
        RecoveryWorker zombie = recovery("worker-zombie", gateway, Runnable::run, RecoveryConfig.defaults());

        assertThat(zombie.runOnce()).containsExactly(id);

        assertThat(lab.intent(id).version()).isEqualTo(versionAfterTakeover[0]);
        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.COMPLETE)
                .allSatisfy(a -> assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED));
        assertThat(tracker.advances(id)).as("zombie: one advance, no retry; new owner: the rest").isGreaterThan(1);
        assertThat(lab.intents.findForSystem(id).orElseThrow().leaseOwner()).contains("worker-new");
    }

    // Covers: 7.5 "Who waits" (advance never sleeps: a RECONCILING step returns a future nextAttemptAt and the clock
    // has not moved; only the worker's Sleeper moves time)
    @Test
    void advance_returnsNextAttemptWithoutWaiting() {
        Lab.Worker a = lab.worker("worker-a");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        var before = lab.clock.instant();

        StepResult r = a.orchestrator().advance(id);

        assertThat(r.after()).isEqualTo(RECONCILING);
        assertThat(r.nextAttemptAt()).hasValueSatisfying(t -> assertThat(t).isAfter(before));
        assertThat(lab.clock.instant()).isEqualTo(before);
        assertThat(sleeps).isEmpty();
    }

    // Covers: 7.5, 7.8 (the orchestrator has no way to wait: its source never sleeps and never names the Sleeper)
    @Test
    void orchestrator_sourceNeverSleeps() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/ucplab/core/orchestrator/DefaultOrchestrator.java"));

        assertThat(source).doesNotContain("Thread.sleep").doesNotContain("Sleeper").doesNotContain("TimeUnit")
                .doesNotContain("synchronized");
    }

    // Covers: 7.6 "Worker id" (unique per process start, from the IdGenerator, never a configured name)
    @Test
    void newWorkerId_isUniquePerProcessStart() {
        SequentialIdGenerator ids = new SequentialIdGenerator("start");

        assertThat(RecoveryWorker.newWorkerId(ids)).isNotEqualTo(RecoveryWorker.newWorkerId(ids)).startsWith("worker-");
    }
}
