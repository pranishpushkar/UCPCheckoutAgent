package dev.ucplab.core.orchestrator;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * A minimal property runner (section 14, level 2) with no property-testing library: generate schedules from one
 * seed, check each, and on the first failure shrink it by removing single entries.
 *
 * <p>Settings come from system properties, passed through by {@code ./mvnw verify -D...}:
 * <ul>
 *   <li>{@code property.seed}: a long, or {@code random} for a fresh seed that is printed. Default {@link #DEFAULT_SEED}.
 *   <li>{@code property.tries}: schedules per property, at least 1. Default {@link #DEFAULT_TRIES}.
 * </ul>
 * A failure reports the seed, the try, the violations, the full schedule and the shrunk one, and how to rerun it.
 */
final class PropertyRunner {

    static final long DEFAULT_SEED = 0x5EED_1A7E_2026L;
    static final int DEFAULT_TRIES = 10_000;
    static final int MAX_SHRINK_ATTEMPTS = 1_000;

    record Settings(long seed, boolean randomSeed, int tries) {

        static Settings fromSystemProperties() {
            String seed = System.getProperty("property.seed", "").trim();
            boolean random = seed.equalsIgnoreCase("random");
            long value = random ? new SplittableRandom().nextLong() : seed.isEmpty() ? DEFAULT_SEED : Long.parseLong(seed);
            String tries = System.getProperty("property.tries", "").trim();
            int n = tries.isEmpty() ? DEFAULT_TRIES : Integer.parseInt(tries);
            if (n < 1) {
                throw new IllegalArgumentException("property.tries must be at least 1: " + n);
            }
            return new Settings(value, random, n);
        }
    }

    /** What one run found: how many schedules ran and how long it took. */
    record Result(String name, int schedules, long millis) {}

    private PropertyRunner() {}

    /** Checks {@code property} (schedule to violations) over the configured schedules; throws on the first failure. */
    static Result check(String name, Function<Schedule, List<String>> property) {
        return check(name, Settings.fromSystemProperties(), property);
    }

    static Result check(String name, Settings settings, Function<Schedule, List<String>> property) {
        if (settings.randomSeed()) {
            System.out.println("[property] " + name + ": property.seed=random chose seed " + settings.seed());
        }
        ScheduleGenerator generator = new ScheduleGenerator(settings.seed());
        long started = System.nanoTime();
        for (int i = 0; i < settings.tries(); i++) {
            Schedule schedule = generator.next();
            List<String> violations = property.apply(schedule);
            if (!violations.isEmpty()) {
                throw new AssertionError(report(name, settings, i, schedule, violations, property));
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[property] %s: %d schedules passed in %.1f s (seed %d)%n",
                name, settings.tries(), millis / 1000.0, settings.seed());
        return new Result(name, settings.tries(), millis);
    }

    /** Greedy shrinking: drop one entry at a time while the property still fails, up to the attempt limit. */
    static Shrunk shrink(Schedule failing, Function<Schedule, List<String>> property) {
        Schedule current = failing;
        List<String> violations = property.apply(failing);
        int attempts = 0;
        boolean progress = true;
        while (progress && attempts < MAX_SHRINK_ATTEMPTS) {
            progress = false;
            for (int i = 0; i < current.size() && attempts < MAX_SHRINK_ATTEMPTS; ) {
                Schedule candidate = current.without(i);
                attempts++;
                List<String> found = property.apply(candidate);
                if (found.isEmpty()) {
                    i++;
                } else {
                    current = candidate;
                    violations = found;
                    progress = true;
                }
            }
        }
        return new Shrunk(current, violations, attempts);
    }

    record Shrunk(Schedule schedule, List<String> violations, int attempts) {}

    private static String report(
            String name, Settings settings, int index, Schedule schedule, List<String> violations,
            Function<Schedule, List<String>> property) {
        Shrunk shrunk = shrink(schedule, property);
        StringBuilder out = new StringBuilder();
        out.append(name).append(" failed on schedule ").append(index + 1).append(" of ").append(settings.tries())
                .append(", seed ").append(settings.seed()).append(settings.randomSeed() ? " (random)" : "").append('\n');
        out.append("rerun: ./mvnw verify -pl agent-core -Dproperty.seed=").append(settings.seed())
                .append(" -Dproperty.tries=").append(index + 1).append('\n');
        out.append("violations:\n");
        violations.forEach(v -> out.append("  ").append(v).append('\n'));
        out.append("schedule (").append(schedule.size()).append(" entries):\n").append(schedule);
        out.append("shrunk to ").append(shrunk.schedule().size()).append(" entries in ").append(shrunk.attempts())
                .append(" attempts:\n").append(shrunk.schedule());
        out.append("shrunk violations:\n");
        shrunk.violations().forEach(v -> out.append("  ").append(v).append('\n'));
        return out.toString();
    }
}
