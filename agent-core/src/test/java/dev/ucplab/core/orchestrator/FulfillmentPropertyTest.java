package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The property tests of 14.1 with the merchant in fulfillment mode (H1 C6): every generated schedule now goes through the
 * shipping selection, whose updates share the schedule's faults, price changes and crashes. Same invariants, same
 * seeds and tries as {@link OrchestratorPropertyTest}, against both merchant modes.
 */
class FulfillmentPropertyTest {

    private static void check(String invariant, FaultyMerchantModel.Mode mode,
            Function<Observations, List<String>> property) {
        ScheduleExecutor executor = ScheduleExecutor.withFulfillment(mode);
        PropertyRunner.check(invariant + " [" + mode + ", fulfillment]", schedule -> property.apply(executor.run(schedule)));
    }

    // Covers: I1, S2, S3, S4, 7.2 (shipping selection), 14.1
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void i1_atMostOneOrderPerIntent_withFulfillment(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I2, I3, S5, S16, P7, P8, 9.2 (snapshot with shipping), 14.1
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void i3_completeOnlyUnderMatchingApproval_withFulfillment(FaultyMerchantModel.Mode mode) {
        check("I3", mode, Invariants::i3);
    }

    // Covers: I4, S2, S9, 14.1
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void i4_sessionNeverChangesAfterFirstComplete_withFulfillment(FaultyMerchantModel.Mode mode) {
        check("I4", mode, Invariants::i4);
    }

    // Covers: I5, 7.5, 7.6, 14.1
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void i5_everyIntentTerminalOrEscalatedWithinItsBound_withFulfillment(FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: I7, S2, S3, S16, 7.4, 14.1
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void i7_resendsReuseKeyAndBody_withFulfillment(FaultyMerchantModel.Mode mode) {
        check("I7", mode, Invariants::i7);
    }

    // Covers: 7.2, 14.1 (reach: with fulfillment mode on, generated schedules do send selection updates, and with it off
    // they do not, unless the schedule itself re-quotes: the properties above are not vacuous)
    @org.junit.jupiter.api.Test
    void theSchedulesReachTheSelectionStep() {
        ScheduleExecutor on = ScheduleExecutor.withFulfillment(FaultyMerchantModel.Mode.LAB);
        ScheduleExecutor off = new ScheduleExecutor(ScheduleExecutor.DEFAULT_ORCHESTRATOR, FaultyMerchantModel.Mode.LAB);
        long updatesOn = 0;
        long updatesOff = 0;
        long seed = dev.ucplab.core.PropertySettings.fromSystemProperties(PropertyRunner.DEFAULT_TRIES).seed();
        for (int i = 0; i < 200; i++, seed = dev.ucplab.core.PropertySettings.next(seed)) {
            Schedule schedule = new ScheduleGenerator(seed).generate();
            updatesOn += on.run(schedule).merchantLog().stream()
                    .filter(c -> c.call() == dev.ucplab.core.merchant.fake.MerchantCall.UPDATE).count();
            updatesOff += off.run(schedule).merchantLog().stream()
                    .filter(c -> c.call() == dev.ucplab.core.merchant.fake.MerchantCall.UPDATE).count();
        }
        org.assertj.core.api.Assertions.assertThat(updatesOn).isGreaterThan(updatesOff + 100);
    }
}
