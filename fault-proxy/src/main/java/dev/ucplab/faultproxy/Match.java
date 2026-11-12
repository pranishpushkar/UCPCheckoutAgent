package dev.ucplab.faultproxy;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * What a rule matches and when it fires (§12 Matching). {@code method} and {@code pathPattern} decide whether a request
 * matches (absent: any); {@code nth} and {@code probability} decide whether the k-th match fires.
 */
public record Match(Optional<String> method, Optional<PathPattern> pathPattern, OptionalInt nth,
                    OptionalDouble probability) {

    public Match {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pathPattern, "pathPattern");
        Objects.requireNonNull(nth, "nth");
        Objects.requireNonNull(probability, "probability");
        method.ifPresent(m -> {
            if (!m.matches("[A-Z]+")) {
                throw new IllegalArgumentException("match.method must be an upper-case HTTP method: " + m);
            }
        });
        if (nth.isPresent() && nth.getAsInt() < 1) {
            throw new IllegalArgumentException("match.nth must be at least 1: " + nth.getAsInt());
        }
        if (probability.isPresent()) {
            double p = probability.getAsDouble();
            if (!(p >= 0.0 && p <= 1.0)) {
                throw new IllegalArgumentException("match.probability must be between 0 and 1: " + p);
            }
        }
    }

    public static Match any() {
        return new Match(Optional.empty(), Optional.empty(), OptionalInt.empty(), OptionalDouble.empty());
    }

    public static Match of(String method, String pathPattern) {
        return new Match(Optional.of(method), Optional.of(new PathPattern(pathPattern)), OptionalInt.empty(),
                OptionalDouble.empty());
    }

    public Match withNth(int n) {
        return new Match(method, pathPattern, OptionalInt.of(n), probability);
    }

    public Match withProbability(double p) {
        return new Match(method, pathPattern, nth, OptionalDouble.of(p));
    }

    /** Whether the request matches, which is what increments the rule's counter. */
    public boolean fits(String requestMethod, String rawPath) {
        return method.map(m -> m.equals(requestMethod)).orElse(true)
                && pathPattern.map(p -> p.matches(rawPath)).orElse(true);
    }
}
