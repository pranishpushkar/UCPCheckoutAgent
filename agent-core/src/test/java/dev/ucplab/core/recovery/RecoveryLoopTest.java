package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.InMemoryRecoveryScan;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** RecoveryLoop (7.6): once at startup, then every period; a failing pass does not stop it. */
class RecoveryLoopTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    private final List<IntentId> advanced = new ArrayList<>();
    private final List<Object[]> schedules = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private boolean failNext;

    private final Orchestrator orchestrator = new Orchestrator() {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String rationale) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("boom");
            }
            advanced.add(id);
            return new StepResult(id, IntentState.QUOTING, IntentState.QUOTING, true, Optional.empty());
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    };

    /** A scheduler that records the schedule and lets the test run its task by hand. */
    private final ScheduledExecutorService scheduler = (ScheduledExecutorService) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {ScheduledExecutorService.class}, (proxy, method, args) -> {
                if (method.getName().equals("scheduleWithFixedDelay")) {
                    tasks.add((Runnable) args[0]);
                    schedules.add(new Object[] {args[1], args[2], args[3]});
                    return (ScheduledFuture<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] {ScheduledFuture.class}, (p, m, a) -> m.getName().equals("cancel") ? true : null);
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private RecoveryWorker worker() {
        return new RecoveryWorker(intents, new InMemoryRecoveryScan(intents, new InMemoryApprovalRepository()),
                orchestrator, clock, d -> { }, Runnable::run, RecoveryConfig.defaults(), "worker-loop");
    }

    private IntentId quoting() {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId("q-1"), new UserId("alice"), new MerchantId("shop-1"),
                "d", List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(IntentState.QUOTING, T0);
        intents.insert(intent);
        return intent.id();
    }

    // Covers: 7.6 (a pass at startup with no delay, then every 5 s counted from the end of the previous pass)
    @Test
    void start_schedulesStartupPassNowAndThenEveryPeriod() {
        new RecoveryLoop(worker(), RecoveryConfig.defaults(), scheduler).start();

        assertThat(schedules).singleElement().satisfies(s -> {
            assertThat(s[0]).isEqualTo(0L);
            assertThat(s[1]).isEqualTo(5000L);
            assertThat(s[2]).isEqualTo(TimeUnit.MILLISECONDS);
        });
    }

    // Covers: 7.6 (each tick is one pass of the worker)
    @Test
    void tick_runsOnePass() {
        IntentId id = quoting();
        new RecoveryLoop(worker(), RecoveryConfig.defaults(), scheduler).start();

        tasks.get(0).run();

        assertThat(advanced).containsExactly(id);
    }

    // Covers: 7.6 (a pass that fails is logged and the loop carries on: the next tick runs normally, and the failed pass
    // claimed nothing it left stranded)
    @Test
    void tick_survivesAFailingPass() {
        IntentId id = quoting();
        boolean[] failScan = {true};
        RecoveryScan flaky = (now, limit) -> {
            if (failScan[0]) {
                failScan[0] = false;
                throw new IllegalStateException("scan failed");
            }
            return List.of();
        };
        RecoveryWorker worker = new RecoveryWorker(intents, flaky, orchestrator, clock, d -> { }, Runnable::run,
                RecoveryConfig.defaults(), "worker-loop");
        new RecoveryLoop(worker, RecoveryConfig.defaults(), scheduler).start();

        tasks.get(0).run();
        assertThat(advanced).isEmpty();
        tasks.get(0).run();

        assertThat(advanced).containsExactly(id);
    }

    // Covers: 7.6 (an executor that refuses a task does not stop the pass or leave the intent marked in flight)
    @Test
    void runOnce_survivesAnExecutorThatRefuses() {
        IntentId id = quoting();
        boolean[] refuse = {true};
        RecoveryWorker worker = new RecoveryWorker(intents, (now, limit) -> List.of(), orchestrator, clock, d -> { },
                r -> {
                    if (refuse[0]) {
                        refuse[0] = false;
                        throw new IllegalStateException("rejected");
                    }
                    r.run();
                }, RecoveryConfig.defaults(), "worker-loop");

        assertThat(worker.runOnce()).isEmpty();
        clock.advance(Duration.ofSeconds(31));

        assertThat(worker.runOnce()).containsExactly(id);
    }

    // Covers: 7.6 (starting twice schedules once)
    @Test
    void start_isIdempotent() {
        RecoveryLoop loop = new RecoveryLoop(worker(), RecoveryConfig.defaults(), scheduler);
        loop.start();
        loop.start();

        assertThat(schedules).hasSize(2); // the second schedule is cancelled at once
    }

    // Covers: 7.6 (settings are validated)
    @Test
    void recoveryConfig_rejectsNonPositiveValues() {
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ZERO, Duration.ofSeconds(30), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 7.6 (lease rules: the worker period is at most half the lease, so a pass always comes round before a
    // lease it renewed can lapse; exactly half is allowed, and so are the defaults)
    @Test
    void recoveryConfig_rejectsAPeriodLongerThanHalfTheLease() {
        assertThatThrownBy(() -> new RecoveryConfig(Duration.ofSeconds(20), Duration.ofSeconds(30), 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7.6")
                .hasMessageContaining("PT20S")
                .hasMessageContaining("PT30S");
        assertThat(new RecoveryConfig(Duration.ofSeconds(15), Duration.ofSeconds(30), 1, 1).period())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(RecoveryConfig.defaults().period()).isEqualTo(Duration.ofSeconds(5));
    }
}
