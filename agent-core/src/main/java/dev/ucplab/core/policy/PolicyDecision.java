package dev.ucplab.core.policy;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code PolicyDecision{allowed, ruleId, reason}} (9.1). A denial names the first rule that failed;
 * an allow names none. Every decision is stored as an event by the caller.
 */
public record PolicyDecision(boolean allowed, Optional<PolicyRule> ruleId, String reason) {

    public PolicyDecision {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(reason, "reason");
        if (allowed == ruleId.isPresent()) {
            throw new IllegalArgumentException("a denial needs a rule id and an allow must not have one");
        }
    }

    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(true, Optional.empty(), reason);
    }

    public static PolicyDecision deny(PolicyRule rule, String reason) {
        return new PolicyDecision(false, Optional.of(Objects.requireNonNull(rule, "rule")), reason);
    }
}
