package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.PropertySettings;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/**
 * The seeded property tests of {@link HandoffPropertyTest} with the handoff follow-up driven (7.6, 14.1): after the
 * handoff the runs keep reading each watched intent at its next read, between the schedule's crashes, faults and
 * registry flips. The follow-up only reads and never moves the intent, and I1, I5 and I12 still hold.
 */
class HandoffFollowUpPropertyTest {

    private static void check(String invariant, FaultyMerchantModel.Mode mode,
            Function<Observations, List<String>> property) {
        ScheduleExecutor executor = ScheduleExecutor.atHandoffWithFollowUp(mode);
        PropertyRunner.check(invariant + " [follow-up, " + mode + "]", schedule -> property.apply(executor.run(schedule)));
    }

    // Covers: 7.6 handoff follow-up, I1, I4, 14.1 Merchant strictness (once HANDED_OFF, an intent stays HANDED_OFF and
    // only GETs reach the store on its session)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_theFollowUpOnlyReads_andNeverMovesTheIntent(FaultyMerchantModel.Mode mode) {
        check("follow-up", mode, Invariants::followUpOnlyReads);
    }

    // Covers: I1, 7.6, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_atMostOneOrderPerIntent_withTheFollowUp(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I12, 7.6, 14.1 Merchant strictness (with the entry flipped at random points, no mutating call reaches a
    // merchant failing I12, follow-up reads included)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_noMutatingCallReachesAMerchantFailingI12_withTheFollowUp(FaultyMerchantModel.Mode mode) {
        check("I12", mode, Invariants::i12);
    }

    // Covers: I5, 7.6, 14.1 Merchant strictness (the follow-up of a terminal intent keeps no other intent from its bound)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_everyIntentTerminalOrEscalatedWithinItsBound_withTheFollowUp(FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: 14.1 Reach check, 7.6 (the runs make follow-up reads: handed-off intents get GETs on their session after
    // the handoff, recorded as follow-up attempts, so the property is not vacuous)
    @Test
    void generator_defaultSeed_reachesFollowUpReads() {
        ScheduleExecutor executor = ScheduleExecutor.atHandoffWithFollowUp(FaultyMerchantModel.Mode.LAB);
        int handedOff = 0;
        int readsAfter = 0;
        long seed = PropertySettings.DEFAULT_SEED;
        for (int n = 0; n < 500; n++, seed = PropertySettings.next(seed)) {
            Observations o = executor.run(new ScheduleGenerator(seed).generate());
            for (Observations.Sample first : o.samples().stream().filter(s -> s.state() == IntentState.HANDED_OFF)
                    .collect(java.util.stream.Collectors.toMap(Observations.Sample::intent, s -> s, (a, b) -> a))
                    .values()) {
                handedOff++;
                readsAfter += (int) o.calls().stream().filter(c -> c.seq() > first.seq() && c.call() == MerchantCall.GET
                        && c.session().equals(first.session())).count();
            }
            assertThat(o.attemptOps().values()).noneMatch(ops -> ops.contains(AttemptOp.COMPLETE));
        }
        assertThat(handedOff).as("intents handed off").isPositive();
        assertThat(readsAfter).as("follow-up reads after a handoff").isGreaterThan(handedOff);
    }
}
