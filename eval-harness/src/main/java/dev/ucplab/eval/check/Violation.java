package dev.ucplab.eval.check;

import java.util.Objects;

/** One invariant violation found in one run's evidence. {@code detail} names ids, keys and times, never a body. */
public record Violation(String invariant, String detail) {
    public Violation {
        Objects.requireNonNull(invariant, "invariant");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
        return invariant + ": " + detail;
    }
}
