package dev.ucplab.service.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every string of every structured log event, the message and the exception text among them (11, I8), through
 * {@link Redaction}. Named by {@code logging.structured.json.customizer}.
 */
public final class RedactingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, Redaction::mask));
    }
}
