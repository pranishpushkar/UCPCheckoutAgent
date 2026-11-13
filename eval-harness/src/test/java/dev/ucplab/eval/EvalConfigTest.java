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

    // Covers: 13.1 (a scenario's seeds come from its catalogue id, so adding rows never moves another row's seeds)
    @Test
    void seedIndex_followsTheCatalogueId() {
        assertThat(Scenarios.seedIndex(Scenarios.S0)).isZero();
        assertThat(Scenarios.seedIndex(Scenarios.S4)).isEqualTo(8);
        assertThat(Scenarios.seedIndex(Scenarios.S11)).isEqualTo(22);
        assertThat(Scenarios.all()).extracting(Scenarios::seedIndex).doesNotHaveDuplicates();
    }

    // Covers: 13.5, m4-plan section 4 (the reduced run gives S9 and S28 fewer runs; their seeds are the same as in a
    // full run, and such a configuration is never the acceptance one)
    @Test
    void fewerRuns_namedRowsGetTheirCount_andTheConfigIsNotFull() {
        EvalConfig c = new EvalConfig(200, TimeScale.of(1), 16, 42, java.util.Map.of("S9", 6, "S28", 2));

        assertThat(c.runsOf("S9")).isEqualTo(6);
        assertThat(c.runsOf("S28")).isEqualTo(2);
        assertThat(c.runsOf("S0")).isEqualTo(200);
        assertThat(c.fewerRunsText()).isEqualTo("S28 2, S9 6");
        assertThat(c.seed(18, 5)).isEqualTo(new EvalConfig(200, TimeScale.of(1), 16, 42).seed(18, 5));
        assertThat(c.full()).isFalse();
        assertThatThrownBy(() -> new EvalConfig(20, TimeScale.of(4), 8, 42, java.util.Map.of("S9", 21)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S9");
        assertThatThrownBy(() -> new EvalConfig(20, TimeScale.of(4), 8, 42, java.util.Map.of("S9", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
