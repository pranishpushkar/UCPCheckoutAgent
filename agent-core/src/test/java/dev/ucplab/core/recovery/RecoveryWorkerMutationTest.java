package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code RecoveryWorker} does with the intents a pass finds (7.6, 7.8, I1, I5), found unchecked by the H3 P6
 * mutation pass: which intents join a batch, which get their lease renewed first, how a parked intent is left alone until
 * it is due, when a driver gives up, what {@code driveNow} refuses, and what an operator reads in the log. The orchestrator
 * is a script and the repository is the in-memory one behind a recorder, so every call the worker makes is visible.
 */
class RecoveryWorkerMutationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String WORKER = "worker-m";
    private static final RecoveryConfig CONFIG = RecoveryConfig.defaults();

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository real = new InMemoryIntentRepository(clock);
    /** Every repository call and every advance, in order: {@code method:intent} or {@code advance:intent}. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Object[], Object>> overrides = new HashMap<>();
    private final List<IntentId> due = new ArrayList<>();
    private final Map<IntentId, Deque<Object>> script = new HashMap<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger workerLog = Logger.getLogger(RecoveryWorker.class.getName());

    @BeforeEach
    void attach() {
        workerLog.addHandler(capture);
        workerLog.setLevel(Level.ALL);
    }

    @AfterEach
    void detach() {
        workerLog.removeHandler(capture);
        workerLog.setLevel(null);
    }

    private String logged(Level level) {
        SimpleFormatter f = new SimpleFormatter();
        StringBuilder out = new StringBuilder();
        for (LogRecord r : records) {
            if (r.getLevel().equals(level)) {
                out.append(f.formatMessage(r)).append('\n');
            }
        }
        return out.toString();
    }

    private IntentRepository recorded() {
        return (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, method, args) -> {
                    String target = args != null && args.length > 0 && args[0] instanceof IntentId i ? ":" + i.value() : "";
                    calls.add(method.getName() + target);
                    Function<Object[], Object> override = overrides.get(method.getName());
                    if (override != null) {
                        return override.apply(args);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** What an advance answers: idle, or not idle with the time of the next attempt. */
    private static StepResult step(IntentId id, boolean idle, Optional<Instant> next) {
        return new StepResult(id, IntentState.QUOTING, IntentState.QUOTING, idle, next);
    }

    private final Orchestrator orchestrator = new Orchestrator() {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why, Optional<Budget> budget) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            calls.add("advance:" + id.value());
            Deque<Object> next = script.get(id);
            Object answer = next == null || next.isEmpty() ? null : next.poll();
            if (answer instanceof RuntimeException e) {
                throw e;
            }
            return answer == null ? step(id, true, Optional.empty()) : (StepResult) answer;
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    };

    private final RecoveryScan scan = (now, limit) -> List.copyOf(due);

    private RecoveryWorker worker(RecoveryConfig config, Executor executor) {
        return new RecoveryWorker(recorded(), scan, orchestrator, clock, sleeps::add, executor, config, WORKER);
    }

    private RecoveryWorker worker() {
        return worker(CONFIG, Runnable::run);
    }

    private IntentId quoting(String id) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), new UserId("alice"), new MerchantId("shop-1"),
                "d-" + id, List.of(new ProposedLine("item_123", 1)), "a@example.com", clock.instant())
                .withState(IntentState.QUOTING, clock.instant());
        real.insert(intent);
        return intent.id();
    }

    private void script(IntentId id, Object... answers) {
        script.computeIfAbsent(id, k -> new ArrayDeque<>()).addAll(List.of(answers));
    }

    private int advances(IntentId id) {
        return (int) calls.stream().filter(("advance:" + id.value())::equals).count();
    }

    /** Parks {@code id}: its advance asks for a call in 20 s, longer than half the 30 s lease. */
    private void parkOnFirstAdvance(IntentId id) {
        script(id, step(id, false, Optional.of(T0.plusSeconds(20))));
    }

    // ---------------------------------------------------------------- batches and leases

    // Covers: 7.6. Kills RecoveryWorker.runOnce:113 ConditionalsBoundary, RemoveConditional_ORDER_IF and EQUAL_IF (the
    // intents the scan finds join the batch only while it is below the limit, and only once): with a limit of 2 and two
    // claimed intents the scan's intent waits for a later pass; with a limit of 3 it is driven after them, and one the
    // claim already returned is driven once
    @Test
    void runOnce_addsScanFoundIntentsOnlyBelowTheLimit_andOnlyOnce() {
        IntentId q1 = quoting("q1");
        IntentId q2 = quoting("q2");
        IntentId a1 = new IntentId("a1");
        due.addAll(List.of(a1, q1));

        List<IntentId> full = worker(new RecoveryConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 2, 500), Runnable::run)
                .runOnce();
        assertThat(full).containsExactly(q1, q2);
        assertThat(advances(a1)).isZero();

        calls.clear();
        List<IntentId> roomy = worker(new RecoveryConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 3, 500), Runnable::run)
                .runOnce();
        assertThat(roomy).containsExactly(q1, q2, a1);
        assertThat(advances(q1)).isEqualTo(1);
    }

    // Covers: 7.6, I1. Kills RecoveryWorker.runOnce:121 RemoveConditional_ORDER_ELSE (a claimed intent has its lease renewed
    // before its first advance; one the scan found has not, because advance takes the lease itself)
    @Test
    void runOnce_renewsTheLeaseOfClaimedIntentsBeforeAdvancing_butNotOfScanFoundOnes() {
        IntentId q1 = quoting("q1");
        IntentId a1 = new IntentId("a1");
        due.add(a1);

        worker().runOnce();

        assertThat(calls.indexOf("renewLease:q1")).isGreaterThan(-1).isLessThan(calls.indexOf("advance:q1"));
        assertThat(calls).doesNotContain("renewLease:a1").contains("advance:a1");
        assertThat(q1).isNotNull();
    }

    // ---------------------------------------------------------------- parked intents

    // Covers: 7.5, 7.6. Kills RecoveryWorker.runOnce:123 and :124 RemoveConditional_EQUAL_ELSE and
    // RecoveryWorker.lambda$forgetDueUnclaimed$2:239 RemoveConditional_EQUAL_IF: an intent parked for a later attempt is
    // left alone until it is due, even when the scan names it, and is not forgotten before its time; once due it is
    // driven and its entry removed
    @Test
    void aParkedIntent_isLeftAlone_untilItIsDue() {
        IntentId q1 = quoting("q1");
        parkOnFirstAdvance(q1);
        RecoveryWorker worker = worker();
        worker.runOnce();
        assertThat(worker.parkedCount()).isEqualTo(1);
        due.add(q1);

        clock.advance(Duration.ofSeconds(5));
        List<IntentId> early = worker.runOnce();

        assertThat(early).isEmpty();
        assertThat(advances(q1)).isEqualTo(1);
        assertThat(worker.parkedCount()).as("not forgotten before it is due").isEqualTo(1);

        clock.advance(Duration.ofSeconds(15));
        List<IntentId> onTime = worker.runOnce();

        assertThat(onTime).containsExactly(q1);
        assertThat(advances(q1)).isEqualTo(2);
        assertThat(worker.parkedCount()).isZero();
    }

    // Covers: 7.6. Kills RecoveryWorker.renewParked:222 RemoveConditional_EQUAL_IF: a parked intent whose lease can no longer
    // be renewed is forgotten and found again by the claim like any other, not kept out of every pass
    @Test
    void aParkedIntentWhoseLeaseIsGone_isForgotten_andDrivenAgain() {
        IntentId q1 = quoting("q1");
        parkOnFirstAdvance(q1);
        RecoveryWorker worker = worker();
        worker.runOnce();
        assertThat(worker.parkedCount()).isEqualTo(1);
        int[] failures = {1};
        overrides.put("renewLease", args -> failures[0]-- > 0 ? Boolean.FALSE : real.renewLease((IntentId) args[0],
                (String) args[1], (Duration) args[2]));

        clock.advance(Duration.ofSeconds(5));
        List<IntentId> second = worker.runOnce();

        assertThat(second).containsExactly(q1);
        assertThat(advances(q1)).isEqualTo(2);
        assertThat(worker.parkedCount()).isZero();
    }

    // ---------------------------------------------------------------- driving

    // Covers: 7.6, I5. Kills RecoveryWorker.drive:244 ConditionalsBoundary and RemoveConditional_ORDER_IF and :269
    // VoidMethodCall (the log): one pass makes at most maxStepsPerIntent advances of an intent, and says so in the log
    @Test
    void aDriver_stopsAtTheStepLimit_andSaysSo() {
        IntentId q1 = quoting("q1");
        for (int i = 0; i < 5; i++) {
            script(q1, step(q1, false, Optional.empty()));
        }

        worker(new RecoveryConfig(Duration.ofSeconds(5), Duration.ofSeconds(30), 50, 3), Runnable::run).runOnce();

        assertThat(advances(q1)).isEqualTo(3);
        assertThat(logged(Level.WARNING)).contains("intent q1 used 3 steps in one pass; leaving it for the next");
    }

    // Covers: 7.6, 7.8. Kills RecoveryWorker.drive:246 RemoveConditional_EQUAL_ELSE and :247 VoidMethodCall: a claimed
    // intent whose lease cannot be renewed is dropped before it is advanced, and the log says the lease was lost
    @Test
    void aClaimedIntentWhoseLeaseCannotBeRenewed_isDropped_beforeAnAdvance() {
        IntentId q1 = quoting("q1");
        overrides.put("renewLease", args -> Boolean.FALSE);

        worker().runOnce();

        assertThat(advances(q1)).isZero();
        assertThat(logged(Level.INFO)).contains("lease lost for intent q1; dropping it for this pass");
    }

    // Covers: 7.6, 7.8, S15. Kills RecoveryWorker.drive:259 VoidMethodCall: an intent taken over mid-step is dropped for the
    // pass, and the log names which of the two it was
    @Test
    void anIntentTakenOverMidStep_isDropped_andTheLogNamesHow() {
        IntentId q1 = quoting("q1");
        IntentId q2 = quoting("q2");
        script(q1, new LeaseLostException(q1, "other"));
        script(q2, new StaleVersionException(q2, 1, 2));

        worker().runOnce();

        assertThat(advances(q1)).isEqualTo(1);
        assertThat(advances(q2)).isEqualTo(1);
        assertThat(logged(Level.INFO)).contains("intent q1 was taken over mid-step (LeaseLostException)")
                .contains("intent q2 was taken over mid-step (StaleVersionException)");
    }

    // Covers: 7.5, 7.8. Kills RecoveryWorker.waitUntil:282 RemoveConditional_EQUAL_ELSE and EQUAL_IF: a next attempt that is
    // due now (or already past) is not waited for: the driver goes on at once without calling the sleeper
    @Test
    void aNextAttemptThatIsDueNow_isNotSleptFor() {
        IntentId q1 = quoting("q1");
        IntentId q2 = quoting("q2");
        script(q1, step(q1, false, Optional.of(T0)));
        script(q2, step(q2, false, Optional.of(T0.minusSeconds(3))));

        worker().runOnce();

        assertThat(sleeps).isEmpty();
        assertThat(advances(q1)).isEqualTo(2);
        assertThat(advances(q2)).isEqualTo(2);
    }

    // Covers: 7.5, 7.8. Kills RecoveryWorker.waitUntil:293 VoidMethodCall (NO_COVERAGE) and :294 BooleanTrueReturnVals: a
    // driver interrupted while it waits stops (no further advance) and leaves the interrupt flag set for its thread
    @Test
    void aDriverInterruptedWhileWaiting_stops_andKeepsTheInterrupt() {
        IntentId q1 = quoting("q1");
        script(q1, step(q1, false, Optional.of(T0.plusSeconds(10))));
        RecoveryWorker worker = new RecoveryWorker(recorded(), scan, orchestrator, clock, d -> {
            throw new InterruptedException("stop");
        }, Runnable::run, CONFIG, WORKER);

        worker.runOnce();

        assertThat(advances(q1)).isEqualTo(1);
        assertThat(Thread.interrupted()).as("the interrupt flag is set again").isTrue();
    }

    // Covers: 7.6, 8. Kills RecoveryWorker.runOnce:144 VoidMethodCall: an executor that refuses the driver is logged with
    // the fixed reason, never the exception's text, and the intent is picked again by the next pass
    @Test
    void anExecutorThatRefuses_isLogged_withAFixedReason() {
        IntentId q1 = quoting("q1");
        Executor refusing = r -> {
            throw new RejectedExecutionException("queue full: secret-canary");
        };

        List<IntentId> started = worker(CONFIG, refusing).runOnce();

        assertThat(started).isEmpty();
        assertThat(logged(Level.SEVERE)).contains("could not start a driver for intent q1: internal")
                .doesNotContain("secret-canary");
        assertThat(worker().runOnce()).containsExactly(q1);
    }

    @Test
    void workerId_isTheOneItWasBuiltWith() {
        assertThat(worker().workerId()).isEqualTo(WORKER);
    }

    // ---------------------------------------------------------------- driveNow

    // Covers: 7.5, 7.6. Kills RecoveryWorker.driveNow:164 RemoveConditional_EQUAL_ELSE, EQUAL_IF (NO_COVERAGE), :165
    // BooleanTrueReturnVals (NO_COVERAGE): an intent parked for a later attempt is not driven now, and is once it is due
    @Test
    void driveNow_waitsForAParkedIntent_untilItIsDue() {
        IntentId q1 = quoting("q1");
        parkOnFirstAdvance(q1);
        RecoveryWorker worker = worker();
        worker.runOnce();

        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.driveNow(q1)).isFalse();
        assertThat(advances(q1)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(15));
        assertThat(worker.driveNow(q1)).isTrue();
        assertThat(advances(q1)).isEqualTo(2);
        assertThat(worker.parkedCount()).isZero();
    }

    // Covers: 7.6, I1. Kills RecoveryWorker.driveNow:173 RemoveConditional_EQUAL_IF (each of the three refusals) and :192
    // RemoveConditional_EQUAL_ELSE (a refusal frees the intent for a later attempt): an unknown intent, one a pass would not
    // drive, and one another worker holds are not driven, take no lease, and can be driven later
    @Test
    void driveNow_refusesWhatAPassWouldNotDrive_andCanTryAgainLater() {
        IntentId missing = new IntentId("missing");
        IntentId awaiting = new IntentId("aw");
        real.insert(PurchaseIntent.proposed(awaiting, new UserId("alice"), new MerchantId("shop-1"), "d-aw",
                List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(IntentState.AWAITING_APPROVAL, T0));
        IntentId held = quoting("held");
        assertThat(real.claim(held, "other", Duration.ofSeconds(30))).isPresent();
        RecoveryWorker worker = worker();

        assertThat(worker.driveNow(missing)).isFalse();
        assertThat(logged(Level.SEVERE)).as("an unknown intent is refused quietly, not as a failure").isEmpty();
        assertThat(worker.driveNow(awaiting)).isFalse();
        assertThat(calls).doesNotContain("claim:aw");
        assertThat(logged(Level.SEVERE)).isEmpty();
        assertThat(worker.driveNow(held)).isFalse();
        assertThat(logged(Level.SEVERE)).isEmpty();

        clock.advance(Duration.ofSeconds(31));
        assertThat(worker.driveNow(held)).as("the failed attempt left nothing in flight").isTrue();
        assertThat(advances(held)).isEqualTo(1);
    }

    // Covers: 7.6, I8. Kills RecoveryWorker.driveNow:189 VoidMethodCall, :190 BooleanTrueReturnVals (NO_COVERAGE): a failed
    // read is not a reason to throw: driveNow says false, logs a fixed reason (never the exception's text), and frees the
    // intent; an executor that refuses the driver is handled the same way
    @Test
    void driveNow_survivesAFailedReadOrARefusingExecutor() {
        IntentId q1 = quoting("q1");
        overrides.put("findForSystem", args -> {
            throw new IllegalStateException("db down: secret-canary");
        });
        RecoveryWorker worker = worker();

        assertThat(worker.driveNow(q1)).isFalse();

        assertThat(logged(Level.SEVERE)).contains("could not drive intent q1 now: internal").doesNotContain("secret-canary");
        overrides.clear();
        int[] refusals = {1};
        RecoveryWorker flaky = worker(CONFIG, r -> {
            if (refusals[0]-- > 0) {
                throw new RejectedExecutionException("full");
            }
            r.run();
        });
        assertThat(flaky.driveNow(q1)).isFalse();
        assertThat(flaky.driveNow(q1)).isTrue();
    }
}
