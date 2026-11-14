package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * An illegal transition is a programming error (7.2: "throw IllegalTransitionException; never ignore it"), and its message
 * is what the developer reads in the log when it happens: it says whether the table has no row for the state and event, or
 * has rows but no guard held. Those are two different bugs to chase, found as one by the H3 P6 mutation pass.
 */
class TransitionTableDiagnosisTest {

    // Covers: 7.2. Kills TransitionTable.resolve:86 RemoveConditional_EQUAL_ELSE (an event with no row for the state is
    // reported as "not in table 7.2", not as a failed guard)
    @Test
    void anEventWithNoRowForTheState_isReportedAsNotInTheTable() {
        assertThatThrownBy(() -> TransitionTable.resolve(IntentState.COMPLETED, TransitionEvent.QUOTE_READY, Set.of()))
                .isInstanceOf(IllegalTransitionException.class).hasMessageContaining("not in table 7.2")
                .hasMessageNotContaining("no guard holds");
    }

    // Covers: 7.2 (a row whose guard does not hold is reported as a failed guard, naming the guards the rows need)
    @Test
    void anEventWhoseGuardsDoNotHold_isReportedAsNoGuardHolding() {
        assertThatThrownBy(() -> TransitionTable.resolve(IntentState.QUOTING, TransitionEvent.QUOTE_READY, Set.of()))
                .isInstanceOf(IllegalTransitionException.class).hasMessageContaining("no guard holds among")
                .hasMessageNotContaining("not in table");
    }
}
