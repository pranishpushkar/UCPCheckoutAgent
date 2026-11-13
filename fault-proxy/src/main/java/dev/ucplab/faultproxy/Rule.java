package dev.ucplab.faultproxy;

import java.util.Objects;
import java.util.OptionalInt;

/** One rule of a {@link RuleSet} (§12). {@code times} caps how often it fires (absent: unlimited). */
public record Rule(String id, Match match, Action action, OptionalInt times) {

    public Rule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(times, "times");
        if (id.isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
        if (times.isPresent() && times.getAsInt() < 1) {
            throw new IllegalArgumentException("times must be at least 1: " + times.getAsInt());
        }
    }

    public Rule(String id, Match match, Action action) {
        this(id, match, action, OptionalInt.empty());
    }

    public Rule withTimes(int n) {
        return new Rule(id, match, action, OptionalInt.of(n));
    }
}
