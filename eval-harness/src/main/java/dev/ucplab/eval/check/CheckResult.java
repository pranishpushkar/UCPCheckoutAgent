package dev.ucplab.eval.check;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The violations of one run, by invariant id, in the order the checks ran. Every check has an entry. */
public record CheckResult(Map<String, List<Violation>> byInvariant) {

    public CheckResult {
        Map<String, List<Violation>> copy = new LinkedHashMap<>();
        byInvariant.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        byInvariant = Collections.unmodifiableMap(copy);
    }

    public List<Violation> violations(String invariant) {
        return byInvariant.getOrDefault(invariant, List.of());
    }

    public List<Violation> all() {
        return byInvariant.values().stream().flatMap(List::stream).toList();
    }

    public boolean clean() {
        return byInvariant.values().stream().allMatch(List::isEmpty);
    }
}
