package dev.ucplab.core.agent;

import java.util.Objects;

/**
 * A live model's API key (I8, m5-plan section 10). It only ever comes from the {@code GEMINI_API_KEY} environment
 * variable of the shell that runs a command; it is never in {@code EvalConfig}, a {@link RunRecord}, a log, a report or
 * an evidence file. {@link #toString} is {@code ModelKey[redacted]}, and it has no {@code equals}: two keys are never
 * compared or hashed.
 *
 * <p>{@link #reveal} is public only because the model client providers live in another module; the ArchUnit tests of
 * every module allow a call to it from {@code dev.ucplab.adk.gemini} alone (C15), where it is handed to the SDK's
 * client builder and nowhere else.
 */
public final class ModelKey {

    private final String value;

    private ModelKey(String value) {
        this.value = value;
    }

    /** @throws IllegalArgumentException if the key is blank (the message never holds the key) */
    public static ModelKey of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a model key must not be blank");
        }
        return new ModelKey(value);
    }

    /** The key itself, for the SDK's client builder in {@code dev.ucplab.adk.gemini} only (ArchUnit enforces it). */
    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "ModelKey[redacted]";
    }
}
