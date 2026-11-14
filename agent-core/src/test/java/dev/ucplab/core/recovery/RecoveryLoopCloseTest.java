package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code RecoveryLoop.close(Duration)} (7.6, m6-plan section 12): a pass in progress finishes before close returns, a
 * pass that outlasts the grace period is interrupted, and no pass starts after close. Latches, not sleeps.
 */
class RecoveryLoopCloseTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** Counted down when a pass is inside advance. */
    private final CountDownLatch inPass = new CountDownLatch(1);
    /** Counted down to let the pass in advance finish. */
    private final CountDownLatch release = new CountDownLatch(1);
    /** Counted down when the pass in advance saw an interrupt. */
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final AtomicInteger advances = new AtomicInteger();
    private final List<String> order = new CopyOnWriteArrayList<>();

    private final Orchestrator blocking = new Orchestrator() {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            advances.incrementAndGet();
            inPass.countDown();
            try {
                release.await();
                order.add("pass finished");
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new StepResult(id, IntentState.QUOTING, IntentState.QUOTING, true, Optional.empty());
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    };

    @AfterEach
    void stopScheduler() throws InterruptedException {
        release.countDown();
        scheduler.shutdownNow();
        assertThat(scheduler.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private RecoveryLoop loop() {
        PurchaseIntent quoting = PurchaseIntent.proposed(new IntentId("q-1"), new UserId("alice"),
                new MerchantId("shop-1"), "d", List.of(new ProposedLine("item_123", 1)), "a@example.com", T0)
                .withState(IntentState.QUOTING, T0);
        intents.insert(quoting);
        RecoveryWorker worker = new RecoveryWorker(intents, new InMemoryRecoveryScan(intents,
                new InMemoryApprovalRepository()), blocking, clock, d -> { }, Runnable::run, RecoveryConfig.defaults(),
                "worker-close");
        return new RecoveryLoop(worker, RecoveryConfig.defaults(), scheduler);
    }

    // Covers: 7.6, m6-plan section 12 (a pass that is running finishes before close returns, within the grace period)
    @Test
    void close_waitsForTheRunningPass() throws Exception {
        RecoveryLoop loop = loop();
        loop.start();
        assertThat(inPass.await(10, TimeUnit.SECONDS)).isTrue();
        CountDownLatch closed = new CountDownLatch(1);
        boolean[] finished = new boolean[1];
        Thread closer = new Thread(() -> {
            finished[0] = loop.close(Duration.ofSeconds(30));
            order.add("close returned");
            closed.countDown();
        });
        closer.start();
        // Wait, without sleeping, until the closer is parked inside close: then it is provably waiting on the pass.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(closer.getState()).isEqualTo(Thread.State.TIMED_WAITING);
        assertThat(closed.getCount()).as("close has not returned while the pass runs").isEqualTo(1);

        release.countDown();
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(order).containsExactly("pass finished", "close returned");
        assertThat(finished[0]).as("the pass finished inside the grace period").isTrue();
        assertThat(interrupted.getCount()).as("never interrupted").isEqualTo(1);
    }

    // Covers: 7.6, m6-plan section 12 (a pass that outlasts the grace period is interrupted, and close says so)
    @Test
    void close_interruptsAPassThatOutlastsTheGrace() throws Exception {
        RecoveryLoop loop = loop();
        loop.start();
        assertThat(inPass.await(10, TimeUnit.SECONDS)).isTrue();

        boolean finished = loop.close(Duration.ofMillis(50));

        assertThat(finished).isFalse();
        assertThat(interrupted.await(10, TimeUnit.SECONDS)).as("the running pass was interrupted").isTrue();
        assertThat(order).doesNotContain("pass finished");
    }

    // Covers: 7.6, m6-plan section 12 (no new pass starts after close: not a scheduled one, not a direct call, and
    // start after close schedules nothing)
    @Test
    void close_noPassStartsAfterwards() {
        RecoveryLoop loop = loop();
        release.countDown();

        assertThat(loop.close(Duration.ofSeconds(1))).isTrue();
        loop.pass();
        loop.start();
        loop.pass();

        assertThat(advances).hasValue(0);
    }

    // Covers: 7.6 (close with nothing running returns at once and reports a clean stop)
    @Test
    void close_idleLoop_returnsTrue() {
        RecoveryLoop loop = loop();
        release.countDown();
        loop.start();

        assertThat(loop.close(Duration.ofSeconds(5))).isTrue();
    }
}
