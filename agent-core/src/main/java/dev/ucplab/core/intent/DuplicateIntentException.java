package dev.ucplab.core.intent;

/** An insert collided with an existing intent id or with an active intent that has the same dedupe key (7.7). */
public final class DuplicateIntentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateIntentException(String message) {
        super(message);
    }
}
