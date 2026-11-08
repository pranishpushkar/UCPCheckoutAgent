package dev.ucplab.core.intent;

import java.util.Objects;

/** Identifier of a merchant attempt (from IdGenerator). */
public record AttemptId(String value) {

    public AttemptId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AttemptId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
