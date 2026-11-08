package dev.ucplab.core.intent;

import java.util.Objects;

/**
 * What one {@code advance} did (7.3). Appendix B names this type without fields. {@code idle} is true when the step
 * made no merchant call and changed nothing: the intent waits for a human, another worker holds its lease, or it is
 * terminal with nothing owed. A caller polling in RECONCILING gets {@code idle = false} for every read.
 */
public record StepResult(IntentId intentId, IntentState before, IntentState after, boolean idle) {

    public StepResult {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
