package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EvalConfigTest {

    // Covers: 13.1, m4-plan section 4 (seeds are baseSeed + scenarioIndex x 10,000 + run, so one run can be replayed)
    @Test
    void seed_isReplayablePerScenarioAndRun() {
        EvalConfig c = new EvalConfig(200, TimeScale.of(1), 16, 42);

        assertThat(c.seed(0, 0)).isEqualTo(42);
        assertThat(c.seed(2, 17)).isEqualTo(42 + 20_000 + 17);
        assertThatThrownBy(() -> c.seed(0, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.seed(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 13.5 (only 200 runs per scenario at time scale 1 is a full report)
    @Test
    void full_needs200RunsAtTimeScaleOne() {
        assertThat(new EvalConfig(200, TimeScale.of(1), 16, 42).full()).isTrue();
        assertThat(new EvalConfig(200, TimeScale.of(4), 16, 42).full()).isFalse();
        assertThat(new EvalConfig(20, TimeScale.of(1), 8, 42).full()).isFalse();
    }

    // Covers: 13.1 (runs and lanes are bounded)
    @Test
    void constructor_refusesOutOfRangeCounts() {
        assertThatThrownBy(() -> new EvalConfig(0, TimeScale.of(1), 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalConfig(10_001, TimeScale.of(1), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalConfig(1, TimeScale.of(1), 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalConfig(1, TimeScale.of(1), 65, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
