package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.PropertySettings;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The seeded property tests of {@link OrchestratorPropertyTest} at a handoff merchant (H5 C7a, 7.2, 14.1): every intent
 * starts at an owned MCP store whose completion is handoff and whose answers carry a continue_url on its shop domain.
 * The same generator, seed chain and faults; I1, I3, I7 and I12 hold, and no complete is ever sent or written.
 */
class HandoffPropertyTest {

    private static void check(String invariant, FaultyMerchantModel.Mode mode,
            Function<Observations, List<String>> property) {
        ScheduleExecutor executor = ScheduleExecutor.atHandoff(mode);
        PropertyRunner.check(invariant + " [handoff, " + mode + "]", schedule -> property.apply(executor.run(schedule)));
    }

    // Covers: I1, 7.2 Handoff merchants, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_atMostOneOrderPerIntent_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I3, 7.2 Handoff merchants, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_completeOnlyUnderValidApproval_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I3", mode, Invariants::i3);
    }

    // Covers: I7, 7.2 Handoff merchants, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_resendsReuseKeyAndBody_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I7", mode, Invariants::i7);
    }

    // Covers: I5, 7.6 (at a handoff merchant APPROVED ends within 5 minutes of the approval, plus one pass and one GET,
    // whatever the store answers; each crash adds one lease and one pass), 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_everyIntentTerminalOrEscalatedWithinItsBound_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: I12, 7.2 hand_off_refused, 19, 14.1 Merchant strictness (with the entry flipped at random points, no
    // mutating call reaches a merchant failing I12 or changed under its intent)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_noMutatingCallReachesAMerchantFailingI12_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I12", mode, Invariants::i12);
    }

    // Covers: 7.2 Handoff merchants (send_complete is never taken: no complete reaches the merchant and no complete
    // attempt row is written for a handoff intent), 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_noCompleteIsSentOrWritten_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("handoff", mode, Invariants::handoffNeverCompletes);
    }

    // Covers: 14.1, 7.2 (the handoff runs reach the outcomes the properties are about, so they do not pass on
    // schedules that never hand off: an intent proposed at the handoff entry ends HANDED_OFF, REJECTED,
    // FAILED_NOT_CHARGED or ESCALATED, never COMPLETED, some are re-quoted with a get row, and the runs reach the
    // handoff_timeout of a store whose reads keep failing, a refused continue_url and the re-quote cap)
    @Test
    void generator_defaultSeed_reachesHandOffAndARequote() {
        ScheduleExecutor executor = ScheduleExecutor.atHandoff(FaultyMerchantModel.Mode.LAB);
        Set<IntentState> ends = EnumSet.noneOf(IntentState.class);
        int requotes = 0;
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        long seed = PropertySettings.DEFAULT_SEED;
        for (int n = 0; n < 2_000; n++, seed = PropertySettings.next(seed)) {
            Observations o = executor.run(new ScheduleGenerator(seed).generate());
            o.samples().stream().filter(s -> s.phase() == Observations.Phase.END && o.attemptOps().containsKey(s.intent()))
                    .forEach(s -> ends.add(s.state()));
            requotes += (int) o.attemptOps().values().stream().filter(ops -> ops.contains(AttemptOp.GET)).count();
            o.ownStore().endReasons().forEach(r -> reasons.merge(r, 1, Integer::sum));
        }
        assertThat(ends).contains(IntentState.HANDED_OFF, IntentState.REJECTED, IntentState.FAILED_NOT_CHARGED,
                IntentState.ESCALATED).doesNotContain(IntentState.COMPLETED);
        assertThat(requotes).as("intents re-quoted after a hand_off_changed").isPositive();
        assertThat(reasons).as("end reasons of the handoff runs").containsKeys("handoff_timeout",
                "continue_url_not_allowed", "price_unstable");
    }
}
