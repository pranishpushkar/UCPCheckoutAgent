package dev.ucplab.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool call the model asked for (6.5, D2). {@code id} is the model's call id, or one the model client assigns when
 * the model sends none (ADR 0004 item 2); results are matched to calls by it. {@code arguments} is the parsed JSON
 * object, model output and so untrusted: every tool validates it. A JSON null argument is kept as a null value.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (id.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("id and name must not be blank");
        }
        if (arguments.keySet().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("argument names must not be null");
        }
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
