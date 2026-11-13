package dev.ucplab.core.agent;

import java.util.Objects;

/** A tool offered to the model (6.2, 6.5, D2): its name, what it does, and its parameters as a JSON schema. */
public record ToolSpec(String name, String description, String parametersJsonSchema) {
    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
