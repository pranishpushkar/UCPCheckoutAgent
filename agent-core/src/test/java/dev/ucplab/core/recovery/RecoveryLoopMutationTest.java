package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle of {@code RecoveryLoop} (7.6, m6-plan section 12) that the H3 P6 mutation pass found unchecked: a closed
 * loop schedules and runs nothing, a started loop is scheduled once and its task is cancelled when it is closed (by either
 * close), a close that cannot wait still stops the loop, and a pass that is already running is never joined by a second.
 * The scheduler is a recorder, and a pass is counted through the scan, which every pass reads first.
 */
class RecoveryLoopMutationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** The {@code cancel} calls of each future the recorder scheduler handed out. */
    private final List<List<Boolean>> cancels = new ArrayList<>();
    private final AtomicInteger passes = new AtomicInteger();
    private Runnable whileScheduling = () -> { };

    private final ScheduledExecutorService scheduler = (ScheduledExecutorService) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {ScheduledExecutorService.class}, (proxy, method, args) -> {
                if (!method.getName().equals("scheduleWithFixedDelay")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                List<Boolean> cancelled = new ArrayList<>();
                cancels.add(cancelled);
                whileScheduling.run();
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ScheduledFuture.class},
                        (p, m, a) -> {
                            if (m.getName().equals("cancel")) {
                                cancelled.add((Boolean) a[0]);
                                return true;
                            }
                            throw new UnsupportedOperationException(m.getName());
                        });
            });

    private final Orchestrator orchestrator = new Orchestrator() {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    };

    private RecoveryLoop loop(RecoveryScan scan) {
        MutableClock clock = new MutableClock(T0);
        RecoveryWorker worker = new RecoveryWorker(new InMemoryIntentRepository(clock), scan, orchestrator, clock, d -> { },
                Runnable::run, RecoveryConfig.defaults(), "worker-l");
        return new RecoveryLoop(worker, RecoveryConfig.defaults(), scheduler);
    }

    private RecoveryLoop loop() {
        return loop((now, limit) -> {
            passes.incrementAndGet();
            return List.of();
        });
    }

    // Covers: 7.6. Kills RecoveryLoop.start:48 RemoveConditional_EQUAL_ELSE and RecoveryLoop.close:118 VoidMethodCall (a
    // loop that was closed, by either close, schedules nothing when it is started, and runs no pass)
    @Test
    void aClosedLoop_schedulesNothing_andRunsNoPass() {
        RecoveryLoop plain = loop();
        plain.close();
        plain.start();
        plain.pass();

        RecoveryLoop graceful = loop();
        assertThat(graceful.close(Duration.ofMillis(10))).isTrue();
        graceful.start();
        graceful.pass();

        assertThat(cancels).isEmpty();
        assertThat(passes).hasValue(0);
    }

    // Covers: 7.6. Kills RecoveryLoop.start:53 RemoveConditional_EQUAL_IF and EQUAL_ELSE (a second start schedules a second
    // task and cancels it at once, so only one task ever runs; the first is not cancelled)
    @Test
    void aSecondStart_cancelsItsOwnTask_andLeavesTheFirstRunning() {
        RecoveryLoop loop = loop();

        loop.start();
        loop.start();

        assertThat(cancels).hasSize(2);
        assertThat(cancels.get(0)).isEmpty();
        assertThat(cancels.get(1)).containsExactly(false);
    }

    // Covers: 7.6. Kills RecoveryLoop.start:53 RemoveConditional_EQUAL_ELSE (the closed test after the schedule): a close
    // that lands while start is scheduling leaves no running task behind: what start scheduled is cancelled
    @Test
    void aCloseThatLandsWhileStarting_cancelsWhatWasScheduled() {
        RecoveryLoop loop = loop();
        whileScheduling = loop::close;

        loop.start();

        assertThat(cancels).hasSize(1);
        assertThat(cancels.get(0)).containsExactly(false);
    }

    // Covers: 7.6. Kills RecoveryLoop.close:120 RemoveConditional_EQUAL_ELSE and close:99 VoidMethodCall (each close
    // cancels the scheduled task, without interrupting a running pass, and a loop closed with a grace period is closed:
    // it cannot be started again)
    @Test
    void close_cancelsTheScheduledTask_byEitherClose() {
        RecoveryLoop plain = loop();
        plain.start();
        plain.close();
        plain.close();
        assertThat(cancels.get(0)).containsExactly(false);

        RecoveryLoop graceful = loop();
        graceful.start();
        assertThat(graceful.close(Duration.ofMillis(10))).isTrue();
        assertThat(cancels.get(1)).containsExactly(false);
        graceful.start();
        assertThat(cancels).hasSize(2);
    }

    // Covers: 7.6. Kills RecoveryLoop.close:106 VoidMethodCall (NO_COVERAGE): a caller that is interrupted while it waits
    // for the pass is not left able to start another: the loop is closed, the answer is false, and the caller's interrupt
    // flag is set again
    @Test
    void close_byAnInterruptedCaller_stillClosesTheLoop_andKeepsTheInterrupt() {
        RecoveryLoop loop = loop();
        loop.start();
        Thread.currentThread().interrupt();

        boolean clean = loop.close(Duration.ofSeconds(1));

        assertThat(clean).isFalse();
        assertThat(Thread.interrupted()).as("the interrupt flag is set again").isTrue();
        loop.pass();
        assertThat(passes).hasValue(0);
    }

    // Covers: 7.6. Kills RecoveryLoop.close:109 RemoveConditional_EQUAL_IF (a second graceful close, when no pass is
    // running, does not throw: there is no pass thread to interrupt)
    @Test
    void aSecondGracefulClose_doesNotThrow() {
        RecoveryLoop loop = loop();
        assertThat(loop.close(Duration.ofMillis(10))).isTrue();

        assertThatCode(() -> loop.close(Duration.ofMillis(10))).doesNotThrowAnyException();
    }

    // Covers: 7.6, 7.8. Kills RecoveryLoop.pass:60 RemoveConditional_EQUAL_IF and EQUAL_ELSE (a pass that finds another pass
    // running does not run a second one, and the permit it did not take is not released: a later pass runs normally)
    @Test
    void aPassThatFindsAnotherRunning_doesNothing() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecoveryLoop loop = loop((now, limit) -> {
            passes.incrementAndGet();
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        Thread first = new Thread(loop::pass);
        first.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        loop.pass();
        assertThat(passes).as("the second pass did not run").hasValue(1);
        release.countDown();
        first.join(10_000);
        loop.pass();

        assertThat(passes).hasValue(2);
    }

    // ---------------------------------------------------------------- RecoveryConfig

    // Covers: 7.6. Kills RecoveryConfig.<init>:19 and :22 RemoveConditional_EQUAL_ELSE / EQUAL_IF and :32
    // RemoveConditional_ORDER_ELSE: a period or a lease of zero is refused as well as a negative one, each with its own
    // message (an operator who sets lease=0 is told the lease must be positive, not that the period is too long), and so is
    // a step limit below one; the smallest allowed values are accepted
    @Test
    void recoveryConfig_refusesZeroAndNegative_andAcceptsTheSmallestAllowed() {
        Duration thirty = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ZERO, thirty, 1, 1)).hasMessageContaining("period must be positive");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(-1), thirty, 1, 1)).hasMessageContaining("period must be positive");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(1), Duration.ZERO, 1, 1))
                .hasMessageContaining("lease must be positive");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(1), Duration.ofSeconds(-1), 1, 1))
                .hasMessageContaining("lease must be positive");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(1), thirty, 0, 1)).hasMessageContaining("limit must be at least 1");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(1), thirty, 1, 0)).hasMessageContaining("maxStepsPerIntent must be at least 1");
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(1), thirty, 1, -1)).hasMessageContaining("maxStepsPerIntent must be at least 1");
        assertThatCode(() -> new RecoveryConfig(Duration.ofSeconds(15), thirty, 1, 1)).doesNotThrowAnyException();
    }
}
