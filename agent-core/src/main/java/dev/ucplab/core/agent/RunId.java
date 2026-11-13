package dev.ucplab.core.agent;

import java.util.Objects;

/** Identifier of an agent run (section 10 {@code agent_run.id}; UUIDv7 in production, from IdGenerator). */
public record RunId(String value) {

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RunId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
