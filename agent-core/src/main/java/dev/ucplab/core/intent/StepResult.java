package dev.ucplab.core.intent;

import java.util.Objects;

/** What one {@code advance} did (7.3). Appendix B names this type without fields. */
public record StepResult(IntentId intentId, IntentState before, IntentState after) {

    public StepResult {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
