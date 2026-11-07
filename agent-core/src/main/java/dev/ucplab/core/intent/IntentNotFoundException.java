package dev.ucplab.core.intent;

/** A write named an intent or attempt that does not exist. A programming error, not a concurrency outcome. */
public final class IntentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IntentNotFoundException(String message) {
        super(message);
    }
}
