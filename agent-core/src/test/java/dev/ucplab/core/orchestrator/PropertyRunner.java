package dev.ucplab.core.orchestrator;

import dev.ucplab.core.PropertySettings;
import java.util.List;
import java.util.function.Function;

/**
 * The property runner of 14.1, with no property-testing library. Schedule 0 is generated from the base seed and each
 * later one from {@link PropertySettings#next} of the one before, so every schedule has its own seed. On the first
 * failure the runner shrinks the schedule by removing single entries and reports the seed, the full schedule and the
 * smallest failing one. {@code -Dproperty.seed=<that seed> -Dproperty.tries=1} replays exactly that schedule.
 *
 * <p>Settings: {@link PropertySettings} ({@code property.seed}, {@code property.tries}); {@link #DEFAULT_TRIES}
 * schedules per property by default.
 */
final class PropertyRunner {

    static final int DEFAULT_TRIES = 10_000;
    static final int MAX_SHRINK_ATTEMPTS = 1_000;

    /** What one passing run covered. */
    record Result(String name, int schedules, long millis) {}

    record Shrunk(Schedule schedule, List<String> violations, int attempts) {}

    private PropertyRunner() {}

    /** Checks {@code property} (schedule to violations) over the configured schedules; throws on the first failure. */
    static Result check(String name, Function<Schedule, List<String>> property) {
        return check(name, PropertySettings.fromSystemProperties(DEFAULT_TRIES), property);
    }

    static Result check(String name, PropertySettings settings, Function<Schedule, List<String>> property) {
        if (settings.randomSeed()) {
            System.out.println("[property] " + name + ": property.seed=random chose seed " + settings.seed());
        }
        long started = System.nanoTime(); // timing for the report only; no test logic reads it
        long seed = settings.seed();
        for (int i = 0; i < settings.tries(); i++, seed = PropertySettings.next(seed)) {
            Schedule schedule = new ScheduleGenerator(seed).generate();
            List<String> violations = property.apply(schedule);
            if (!violations.isEmpty()) {
                throw new AssertionError(report(name, settings, i, seed, schedule, violations, property));
            }
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[property] %s: %d schedules passed in %.1f s (seed %d)%n",
                name, settings.tries(), millis / 1000.0, settings.seed());
        return new Result(name, settings.tries(), millis);
    }

    /**
     * 14.1 shrinking: repeatedly remove single entries, keeping any shorter schedule that still fails, until no single
     * removal fails or {@link #MAX_SHRINK_ATTEMPTS} attempts are used.
     */
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

    private static String report(
            String name, PropertySettings settings, int index, long seed, Schedule schedule, List<String> violations,
            Function<Schedule, List<String>> property) {
        Shrunk shrunk = shrink(schedule, property);
        StringBuilder out = new StringBuilder();
        out.append(name).append(" failed on schedule ").append(index + 1).append(" of ").append(settings.tries())
                .append(" (base seed ").append(settings.seed()).append(settings.randomSeed() ? ", random" : "")
                .append("), schedule seed ").append(seed).append('\n');
        out.append("replay: ./mvnw verify -pl agent-core ").append(PropertySettings.replay(seed)).append('\n');
        out.append("violations:\n");
        violations.forEach(v -> out.append("  ").append(v).append('\n'));
        out.append("schedule (").append(schedule.size()).append(" entries, seed ").append(seed).append("):\n")
                .append(schedule);
        out.append("smallest failing schedule (").append(shrunk.schedule().size()).append(" entries, shrunk from seed ")
                .append(seed).append(" in ").append(shrunk.attempts()).append(" attempts):\n")
                .append(shrunk.schedule());
        out.append("its violations:\n");
        shrunk.violations().forEach(v -> out.append("  ").append(v).append('\n'));
        return out.toString();
    }
}
