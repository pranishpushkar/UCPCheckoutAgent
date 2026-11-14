package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The seeded property tests of {@link OrchestratorPropertyTest} with two merchants (I11, 8.5, 19): the slots alternate
 * between merchant A and merchant B, the orchestrator picks each intent's gateway by its merchant, and every generated
 * fault, price and stock change and crash applies to both. I1, I4, I5 and I7 are checked over both merchants' orders
 * and calls (one view per merchant, each checked on its own), on the same seed chain and the same number of schedules, against the lab and the strict merchant.
 */
class TwoMerchantPropertyTest {

    private static void check(String invariant, FaultyMerchantModel.Mode mode,
            Function<Observations, List<String>> property) {
        ScheduleExecutor executor = ScheduleExecutor.spread(mode);
        PropertyRunner.check(invariant + " [two merchants, " + mode + "]", schedule -> executor.runViews(schedule).stream().flatMap(v -> property.apply(v).stream()).toList());
    }

    // Covers: I1, I11, 8.5, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_atMostOneOrderPerIntent_withTwoMerchants(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I4, I11, 8.5, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_sessionNeverChangesAfterFirstComplete_withTwoMerchants(FaultyMerchantModel.Mode mode) {
        check("I4", mode, Invariants::i4);
    }

    // Covers: I5, I11, 8.5, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_everyIntentTerminalOrEscalatedWithinItsBound_withTwoMerchants(FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: I7, I11, 8.5, 14.1 Merchant strictness
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_resendsReuseKeyAndBodyAndNewApprovalGetsNewKey_withTwoMerchants(FaultyMerchantModel.Mode mode) {
        check("I7", mode, Invariants::i7);
    }
}
