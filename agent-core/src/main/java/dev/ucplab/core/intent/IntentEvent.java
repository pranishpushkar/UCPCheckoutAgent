package dev.ucplab.core.intent;

import java.time.Instant;
import java.util.Objects;

/**
 * An entry in an intent's append-only event log (section 10, {@code intent_event}). The repository assigns
 * the sequence number. {@code payloadJson} must not contain credentials, tokens or Authorization headers.
 */
public record IntentEvent(String type, String payloadJson, Instant at) {

    public IntentEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(at, "at");
        if (type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
    }
}
