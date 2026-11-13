package dev.ucplab.eval.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The statistics of 13.4: Wilson 95% intervals for rates, and for a violation count the rule of three (3/N) when it is
 * zero, the Wilson 95% upper limit when it is not. Percentiles are nearest-rank.
 */
public final class Stats {

    /** The two-sided 95% normal quantile. */
    public static final double Z95 = 1.959964;

    private Stats() {
    }

    /** A closed interval of rates. */
    public record Interval(double low, double high) {
    }

    /** An upper bound on a rate and the method that gave it. */
    public record Bound(String method, double value) {
    }

    /** The Wilson score interval at 95% for {@code k} successes in {@code n} trials, clipped to [0, 1]. */
    public static Interval wilson(int k, int n) {
        if (n < 1 || k < 0 || k > n) {
            throw new IllegalArgumentException("not a rate: " + k + "/" + n);
        }
        double p = (double) k / n;
        double z2 = Z95 * Z95;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denominator;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        // The interval reaches 0 at k = 0 and 1 at k = n exactly; floating point would leave 1e-16 short.
        double low = k == 0 ? 0 : Math.max(0, centre - half);
        double high = k == n ? 1 : Math.min(1, centre + half);
        return new Interval(low, high);
    }

    /** The 95% upper bound on a rate with no events in {@code n} trials: 3/n (13.4). */
    public static double ruleOfThree(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("no trials");
        }
        return Math.min(1, 3.0 / n);
    }

    /** The 95% upper bound on a violation rate: the rule of three at zero, the Wilson upper limit above it. */
    public static Bound violationBound(int k, int n) {
        return k == 0 ? new Bound("rule_of_three", ruleOfThree(n)) : new Bound("wilson", wilson(k, n).high());
    }

    /** The nearest-rank percentile {@code p} (0 to 100] of {@code values}; empty if there are none. */
    public static Optional<Duration> percentile(List<Duration> values, int p) {
        if (p <= 0 || p > 100) {
            throw new IllegalArgumentException("percentile must be 1 to 100: " + p);
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<Duration> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return Optional.of(sorted.get(Math.max(0, rank - 1)));
    }
}
