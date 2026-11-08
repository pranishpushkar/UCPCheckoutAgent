package dev.ucplab.core.identity;

import java.util.Objects;

/** The signed-in user: the verified OIDC {@code sub} claim, never a value from a request body (T1). Appendix B. */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UserId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
