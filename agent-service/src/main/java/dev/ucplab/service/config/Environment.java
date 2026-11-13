package dev.ucplab.service.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The process environment (25.4). The only class in agent-service that calls {@code System.getenv}, so a secret such as
 * {@code GEMINI_API_KEY} has one way in (I8, ArchitectureTest). It never prints a value: {@link #toString} shows how
 * many settings there are and nothing else.
 */
public final class Environment {

    private final Map<String, String> values;

    private Environment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** The real environment of this process. */
    public static Environment system() {
        return new Environment(System.getenv());
    }

    /** A fixed environment, for tests and for the CLI. */
    public static Environment of(Map<String, String> values) {
        return new Environment(Objects.requireNonNull(values, "values"));
    }

    /** The setting, or empty when it is unset or blank. */
    public Optional<String> get(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(values.get(name)).filter(v -> !v.isBlank());
    }

    @Override
    public String toString() {
        return "Environment[" + values.size() + " settings]";
    }
}
