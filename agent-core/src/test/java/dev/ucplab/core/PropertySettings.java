package dev.ucplab.core;

import java.util.SplittableRandom;

/**
 * Seed and try count for the seeded property tests (14.1), from system properties passed through by
 * {@code ./mvnw verify -D...}:
 * <ul>
 *   <li>{@code property.seed}: the base seed, a long, or {@code random} for a fresh one that the caller prints.
 *       Default {@link #DEFAULT_SEED}, so builds are reproducible.
 *   <li>{@code property.tries}: how many cases to run, at least 1. Defaults to what the test asks for.
 * </ul>
 * Case 0 uses the base seed itself and each later case the {@link #next} of the one before, so the seed reported for
 * any failing case replays it alone with {@code -Dproperty.seed=<seed> -Dproperty.tries=1}.
 */
public record PropertySettings(long seed, boolean randomSeed, int tries) {

    public static final long DEFAULT_SEED = 0x5EED_1A7E_2026L;

    public PropertySettings {
        if (tries < 1) {
            throw new IllegalArgumentException("property.tries must be at least 1: " + tries);
        }
    }

    public static PropertySettings fromSystemProperties(int defaultTries) {
        String seed = System.getProperty("property.seed", "").trim();
        boolean random = seed.equalsIgnoreCase("random");
        long value = random ? new SplittableRandom().nextLong() : seed.isEmpty() ? DEFAULT_SEED : Long.parseLong(seed);
        String tries = System.getProperty("property.tries", "").trim();
        return new PropertySettings(value, random, tries.isEmpty() ? defaultTries : Integer.parseInt(tries));
    }

    /** The seed of the case after the one seeded with {@code seed}. */
    public static long next(long seed) {
        return new SplittableRandom(seed).nextLong();
    }

    /** How to replay one case: the system properties that select it. */
    public static String replay(long caseSeed) {
        return "-Dproperty.seed=" + caseSeed + " -Dproperty.tries=1";
    }
}
