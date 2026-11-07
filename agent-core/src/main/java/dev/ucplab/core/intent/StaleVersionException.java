package dev.ucplab.core.intent;

/** A conditional write found a different version than expected: someone else wrote first (7.6, 7.8). */
public final class StaleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(IntentId id, long expectedVersion, long actualVersion) {
        super("stale version for intent " + id + ": expected " + expectedVersion + ", found " + actualVersion);
    }
}
