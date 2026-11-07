package dev.ucplab.core.intent;

/** An event that table 7.2 does not allow in the current state. A programming error; never ignore it. */
public final class IllegalTransitionException extends RuntimeException {

    private final IntentState from;
    private final TransitionEvent event;

    public IllegalTransitionException(IntentState from, TransitionEvent event, String reason) {
        super(from + " --" + event + "--> " + reason);
        this.from = from;
        this.event = event;
    }

    public IntentState from() {
        return from;
    }

    public TransitionEvent event() {
        return event;
    }
}
