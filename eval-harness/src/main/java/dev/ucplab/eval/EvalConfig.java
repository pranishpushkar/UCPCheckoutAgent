package dev.ucplab.eval;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The settings of one evaluation (13.1, m4-plan section 4): how many runs per scenario, the time scale, how many lanes
 * run side by side, and the base seed. Run seeds are {@code baseSeed + scenarioIndex x 10,000 + run}, so a single run
 * can be replayed on its own.
 *
 * @param fewerRuns rows that run fewer times than {@code runsPerScenario}, by scenario id: the reduced run in verify
 *     gives the rows that start agent child JVMs fewer runs (m4-plan section 4: S9 6, S28 2). Such a report is reduced
 */
public record EvalConfig(int runsPerScenario, TimeScale timeScale, int lanes, long baseSeed,
                         Map<String, Integer> fewerRuns) {

    public static final int MAX_LANES = 64;
    static final int SEEDS_PER_SCENARIO = 10_000;

    public EvalConfig {
        Objects.requireNonNull(timeScale, "timeScale");
        if (runsPerScenario < 1 || runsPerScenario > SEEDS_PER_SCENARIO) {
            throw new IllegalArgumentException("runsPerScenario must be 1 to " + SEEDS_PER_SCENARIO + ": "
                    + runsPerScenario);
        }
        if (lanes < 1 || lanes > MAX_LANES) {
            throw new IllegalArgumentException("lanes must be 1 to " + MAX_LANES + ": " + lanes);
        }
        fewerRuns = Map.copyOf(new TreeMap<>(fewerRuns));
        fewerRuns.forEach((id, n) -> {
            if (n < 1 || n > runsPerScenario) {
                throw new IllegalArgumentException(id + ": fewer runs must be 1 to " + runsPerScenario + ": " + n);
            }
        });
    }

    /** Every row runs {@code runsPerScenario} times. */
    public EvalConfig(int runsPerScenario, TimeScale timeScale, int lanes, long baseSeed) {
        this(runsPerScenario, timeScale, lanes, baseSeed, Map.of());
    }

    /** How many runs the row {@code scenarioId} gets. */
    public int runsOf(String scenarioId) {
        return fewerRuns.getOrDefault(scenarioId, runsPerScenario);
    }

    /** The rows with fewer runs, sorted by id, as {@code "S28 2, S9 6"}; empty if there are none. */
    public String fewerRunsText() {
        return new TreeMap<>(fewerRuns).entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** The seed of run {@code run} (0-based) of the scenario at {@code scenarioIndex}. */
    public long seed(int scenarioIndex, int run) {
        if (scenarioIndex < 0 || run < 0 || run >= runsPerScenario) {
            throw new IllegalArgumentException("no run " + run + " of scenario index " + scenarioIndex);
        }
        return baseSeed + (long) scenarioIndex * SEEDS_PER_SCENARIO + run;
    }

    /** Whether this is the acceptance configuration (13.5): 200 runs per scenario at time scale 1. */
    public boolean full() {
        return runsPerScenario >= 200 && timeScale.k() == 1 && fewerRuns.isEmpty();
    }
}
