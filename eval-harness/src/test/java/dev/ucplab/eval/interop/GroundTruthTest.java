package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The row checks on canned orders (H1 C8b): I1 by checkout id and I2 on every COMPLETED row. */
class GroundTruthTest {

    // Covers: I1, 13.1 (a COMPLETED run whose one new order is another checkout's passes a count but must fail the id)
    @Test
    void aForeignOrder_withTheRightCount_isAViolation() {
        List<String> v = InteropRows.groundTruth(IntentState.COMPLETED, "chk-1", List.of(new SampleOrder("o1", "chk-OTHER", 3500)), 3500);

        assertThat(v).singleElement().asString().contains("I1").contains("chk-1");
    }

    // Covers: I2, 9.2 (the order's total is the approved total, shipping included, on every COMPLETED row)
    @Test
    void anOrderAboveOrBelowTheApprovedTotal_isAViolation() {
        assertThat(InteropRows.groundTruth(IntentState.COMPLETED, "chk-1", List.of(new SampleOrder("o1", "chk-1", 3700)), 3500))
                .singleElement().asString().contains("I2");
        assertThat(InteropRows.groundTruth(IntentState.COMPLETED, "chk-1", List.of(new SampleOrder("o1", "chk-1", 3500)), 3500)).isEmpty();
    }

    // Covers: I1 (two orders for one intent, or any order for a refused one, are violations)
    @Test
    void extraOrders_areViolations() {
        assertThat(InteropRows.groundTruth(IntentState.COMPLETED, "c", List.of(new SampleOrder("a", "c", 1), new SampleOrder("b", "c", 1)), 1))
                .anySatisfy(x -> assertThat(x).contains("I1"));
        assertThat(InteropRows.groundTruth(IntentState.FAILED_NOT_CHARGED, "c", List.of(new SampleOrder("a", "c", 1)), 1)).isNotEmpty();
        assertThat(InteropRows.groundTruth(IntentState.FAILED_NOT_CHARGED, "c", List.of(), 1)).isEmpty();
    }
}
