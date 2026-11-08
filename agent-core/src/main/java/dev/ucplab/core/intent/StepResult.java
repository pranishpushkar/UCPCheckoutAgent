package dev.ucplab.core.intent;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What one {@code advance} did (7.3). Appendix B names this type without fields. {@code idle} is true when the step
 * made no merchant call and changed nothing: the intent waits for a human, another worker holds its lease, or it is
 * terminal with nothing owed.
 *
 * <p>{@code nextAttemptAt} is set when the next step is a poll or a resend (7.5, "Who waits"): the caller waits and
 * calls {@code advance} again at or after that time. {@code advance} itself never sleeps.
 */
public record StepResult(
        IntentId intentId, IntentState before, IntentState after, boolean idle, Optional<Instant> nextAttemptAt) {

    public StepResult {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }
}
