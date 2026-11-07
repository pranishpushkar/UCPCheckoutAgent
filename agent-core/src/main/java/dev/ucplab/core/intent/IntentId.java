package dev.ucplab.core.intent;

import java.util.Objects;

/** Identifier of a purchase intent (UUIDv7 in production, from IdGenerator). */
public record IntentId(String value) {

    public IntentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IntentId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
