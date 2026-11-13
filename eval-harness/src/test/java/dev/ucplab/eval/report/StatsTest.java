package dev.ucplab.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 13.4 against values worked out by hand: the Wilson score interval with z = 1.959964,
 * {@code (p + z^2/2n) / (1 + z^2/n) +- z sqrt(p(1-p)/n + z^2/4n^2) / (1 + z^2/n)}, and the rule of three.
 */
class StatsTest {

    private static final double EPS = 5e-6;

    // Covers: 13.4 (n = 200: k = 0, 1 and 200)
    @Test
    void wilson_twoHundredRuns_matchesHandValues() {
        assertThat(Stats.wilson(0, 200).low()).isEqualTo(0.0);
        assertThat(Stats.wilson(0, 200).high()).isCloseTo(0.018845, within(EPS));
        assertThat(Stats.wilson(1, 200).low()).isCloseTo(0.000883, within(EPS));
        assertThat(Stats.wilson(1, 200).high()).isCloseTo(0.027774, within(EPS));
        assertThat(Stats.wilson(200, 200).low()).isCloseTo(0.981155, within(EPS));
        assertThat(Stats.wilson(200, 200).high()).isEqualTo(1.0);
        assertThat(Stats.wilson(199, 200).low()).isCloseTo(0.972226, within(EPS));
    }

    // Covers: 13.4 (the reduced run: n = 20)
    @Test
    void wilson_twentyRuns_matchesHandValues() {
        assertThat(Stats.wilson(20, 20).low()).isCloseTo(0.838875, within(EPS));
        assertThat(Stats.wilson(0, 20).high()).isCloseTo(0.161125, within(EPS));
        assertThat(Stats.wilson(3, 20).low()).isCloseTo(0.052369, within(EPS));
        assertThat(Stats.wilson(3, 20).high()).isCloseTo(0.360419, within(EPS));
    }

    // Covers: 13.4 (zero violations: the rule of three, 3/N; above zero: the Wilson upper limit)
    @Test
    void violationBound_zeroUsesRuleOfThree_otherwiseWilson() {
        assertThat(Stats.violationBound(0, 200)).isEqualTo(new Stats.Bound("rule_of_three", 0.015));
        assertThat(Stats.violationBound(0, 20).value()).isCloseTo(0.15, within(1e-12));
        assertThat(Stats.violationBound(0, 2).value()).isEqualTo(1.0);
        Stats.Bound one = Stats.violationBound(1, 200);
        assertThat(one.method()).isEqualTo("wilson");
        assertThat(one.value()).isCloseTo(0.027774, within(EPS));
    }

    // Covers: 13.4 (no rate without trials)
    @Test
    void wilson_notARate_isRefused() {
        assertThatThrownBy(() -> Stats.wilson(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stats.wilson(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stats.ruleOfThree(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 13.3 (time to terminal p50 and p95, nearest rank)
    @Test
    void percentile_nearestRank() {
        List<Duration> ms = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(Duration::ofMillis).toList();

        assertThat(Stats.percentile(ms, 50)).contains(Duration.ofMillis(10));
        assertThat(Stats.percentile(ms, 95)).contains(Duration.ofMillis(19));
        assertThat(Stats.percentile(ms, 100)).contains(Duration.ofMillis(20));
        assertThat(Stats.percentile(List.of(Duration.ofMillis(7)), 95)).contains(Duration.ofMillis(7));
        assertThat(Stats.percentile(List.of(), 50)).isEmpty();
    }
}
