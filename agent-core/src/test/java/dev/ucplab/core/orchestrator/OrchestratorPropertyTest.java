package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.ucplab.core.PropertySettings;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Seeded property tests (section 14, level 2; M1 acceptance in section 16): DefaultOrchestrator against
 * FaultyMerchantModel under generated schedules of user actions, per-call faults, merchant-side events, re-quotes
 * (P7, P8) and crash points (7.3, S9). Each invariant is checked on its own run of {@link PropertyRunner#DEFAULT_TRIES}
 * schedules, or {@code -Dproperty.tries} (14.1).
 */
class OrchestratorPropertyTest {

    private final ScheduleExecutor executor = new ScheduleExecutor(ScheduleExecutor.DEFAULT_ORCHESTRATOR);

    private Function<Schedule, List<String>> holds(Function<Observations, List<String>> invariant) {
        return schedule -> invariant.apply(executor.run(schedule));
    }

    // Covers: I1, S2, S3, S4, S5, S6, S7, S9, S13, S16, S17, P7, P8
    @Test
    void property_atMostOneOrderPerIntent_forEveryGeneratedSchedule() {
        PropertyRunner.check("I1", holds(Invariants::i1));
    }

    // Covers: I3, I2, S5, S16, P7, P8, 9.2
    @Test
    void property_completeOnlyUnderValidUnconsumedMatchingApproval_forEveryGeneratedSchedule() {
        PropertyRunner.check("I3", holds(Invariants::i3));
    }

    // Covers: I4, S2, S9, S17
    @Test
    void property_sessionNeverChangesAfterFirstComplete_forEveryGeneratedSchedule() {
        PropertyRunner.check("I4", holds(Invariants::i4));
    }

    // Covers: I5, NFR4, S9, 7.5, 7.6
    @Test
    void property_everyIntentTerminalOrEscalatedByItsDeadline_forEveryGeneratedSchedule() {
        PropertyRunner.check("I5", holds(Invariants::i5));
    }

    // Covers: I7, S2, S3, S9, S16, S17, 7.4
    @Test
    void property_resendsReuseKeyAndBodyAndNewApprovalGetsNewKey_forEveryGeneratedSchedule() {
        PropertyRunner.check("I7", holds(Invariants::i7));
    }

    /** The first {@code n} schedules a run from {@code base} checks (14.1 seed chain). */
    private static List<Schedule> schedules(long base, int n) {
        List<Schedule> out = new ArrayList<>(n);
        for (long seed = base; out.size() < n; seed = PropertySettings.next(seed)) {
            out.add(new ScheduleGenerator(seed).generate());
        }
        return out;
    }

    // Covers: 14.1 Determinism (a schedule depends only on its seed)
    @Test
    void generator_sameSeed_generatesSameSchedule() {
        for (long seed = 7, i = 0; i < 100; i++, seed = PropertySettings.next(seed)) {
            ScheduleGenerator generator = new ScheduleGenerator(seed);
            assertThat(generator.generate()).isEqualTo(new ScheduleGenerator(seed).generate())
                    .isEqualTo(generator.generate());
        }
    }

    // Covers: 14.1 Generator (every kind of entry and fault appears under the default seed)
    @Test
    void generator_defaultSeed_generatesEveryEntryAndFaultKind() {
        Set<String> kinds = new HashSet<>();
        for (Schedule schedule : schedules(PropertySettings.DEFAULT_SEED, 500)) {
            for (ScheduleEntry e : schedule.entries()) {
                kinds.add(e instanceof ScheduleEntry.CallFault f
                        ? "fault:" + f.fault().getClass().getSimpleName()
                        : e.getClass().getSimpleName());
            }
        }
        assertThat(kinds).contains("Propose", "Approve", "ApproveStale", "Reject", "Wait", "SetPrice", "SetStock",
                "Crash", "fault:NotSent", "fault:CommitThenDrop", "fault:DelayResponse", "fault:DelayForward",
                "fault:Duplicate", "fault:PriceChange", "fault:StockChange");
    }

    // Covers: 14.1 Reach check, I5, P8, S9 (the schedules reach every terminal state, fired crashes, same-key
    // resends and the P8 cap, so a passing property is not passing on easy runs only)
    @Test
    void generator_defaultSeed_reachesEveryOutcomeTheInvariantsAreAbout() {
        Set<IntentState> ends = EnumSet.noneOf(IntentState.class);
        int crashes = 0;
        int resendRuns = 0;
        int capRuns = 0;
        for (Schedule schedule : schedules(PropertySettings.DEFAULT_SEED, 2_000)) {
            Observations o = executor.run(schedule);
            o.samples().stream().filter(s -> s.phase() == Observations.Phase.END).forEach(s -> ends.add(s.state()));
            crashes += o.crashes().size();
            List<IdempotencyKey> keys = o.calls().stream().filter(c -> c.call() == MerchantCall.COMPLETE)
                    .map(c -> c.key().orElseThrow()).toList();
            resendRuns += keys.size() > keys.stream().distinct().count() ? 1 : 0;
            capRuns += keys.stream().distinct().count() >= 3 ? 1 : 0;
        }
        assertThat(ends).contains(IntentState.COMPLETED, IntentState.REJECTED, IntentState.FAILED_NOT_CHARGED,
                IntentState.ESCALATED);
        assertThat(crashes).isPositive();
        assertThat(resendRuns).isPositive();
        assertThat(capRuns).isPositive();
    }

    // Covers: 14.1 On failure (shrinking keeps only the entries the failure needs)
    @Test
    void runner_shrink_removesEntriesTheFailureDoesNotNeed() {
        Schedule failing = new Schedule(List.of(
                new ScheduleEntry.Wait(Duration.ofSeconds(1)), new ScheduleEntry.Propose(0),
                new ScheduleEntry.Approve(0), new ScheduleEntry.Propose(1), new ScheduleEntry.Reject(1)));
        Function<Schedule, List<String>> needsApproveOf0 = s -> s.entries().contains(new ScheduleEntry.Approve(0))
                ? List.of("fails") : List.of();

        PropertyRunner.Shrunk shrunk = PropertyRunner.shrink(failing, needsApproveOf0);

        assertThat(shrunk.schedule().entries()).containsExactly(new ScheduleEntry.Approve(0));
        assertThat(shrunk.attempts()).isLessThanOrEqualTo(PropertyRunner.MAX_SHRINK_ATTEMPTS);
    }

    // Covers: 14.1 On failure, Replay (the report names the failing schedule's own seed, and that seed with one try
    // fails on exactly the same schedule)
    @Test
    void runner_failure_reportsScheduleSeedThatReplaysItAlone() {
        Schedule fourth = schedules(42, 4).get(3);
        Function<Schedule, List<String>> failsOnFourth = s -> s.equals(fourth) ? List.of("boom") : List.of();

        AssertionError first = catchThrowableOfType(AssertionError.class,
                () -> PropertyRunner.check("demo", new PropertySettings(42, false, 10), failsOnFourth));
        long seed = Long.parseLong(first.getMessage().replaceAll("(?s).*schedule seed (-?\\d+).*", "$1"));
        AssertionError replay = catchThrowableOfType(AssertionError.class,
                () -> PropertyRunner.check("demo", new PropertySettings(seed, false, 1), failsOnFourth));

        assertThat(first.getMessage()).contains("schedule 4 of 10", "base seed 42", PropertySettings.replay(seed),
                "violations:", "boom", "schedule (" + fourth.size() + " entries", "smallest failing schedule (");
        assertThat(replay.getMessage()).contains("schedule 1 of 1", "schedule seed " + seed)
                .contains(fourth.toString());
    }
}
