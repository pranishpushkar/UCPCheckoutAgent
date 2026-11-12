package dev.ucplab.eval;

import java.util.Objects;

/**
 * The settings of one evaluation (13.1, m4-plan section 4): how many runs per scenario, the time scale, how many lanes
 * run side by side, and the base seed. Run seeds are {@code baseSeed + scenarioIndex x 10,000 + run}, so a single run
 * can be replayed on its own.
 */
public record EvalConfig(int runsPerScenario, TimeScale timeScale, int lanes, long baseSeed) {

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
        return runsPerScenario >= 200 && timeScale.k() == 1;
    }
}
