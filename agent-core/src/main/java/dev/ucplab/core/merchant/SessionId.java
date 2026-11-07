package dev.ucplab.core.merchant;

import java.util.Objects;

/** The merchant's checkout session id. Immutable for an intent once a complete attempt exists (I4). */
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SessionId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
