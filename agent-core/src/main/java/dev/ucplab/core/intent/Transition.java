package dev.ucplab.core.intent;

import java.util.Objects;

/** One entry of table 7.2. */
public record Transition(
        IntentState from, TransitionEvent event, TransitionGuard guard, IntentState to, SideEffect sideEffect) {

    public Transition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(sideEffect, "sideEffect");
    }

    /** Whether this row ends with a session cancel: the intent then carries cancel_pending (7.6). */
    public boolean owesCancel() {
        return switch (sideEffect) {
            case CANCEL_SESSION, CANCEL_SESSION_BEST_EFFORT, REJECT_PRICE_UNSTABLE -> true;
            default -> false;
        };
    }
}
