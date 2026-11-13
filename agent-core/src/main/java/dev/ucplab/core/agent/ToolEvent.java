package dev.ucplab.core.agent;

import java.time.Duration;
import java.util.Objects;

/**
 * One tool call of a run as it is recorded (23.1: names and short summaries, no raw tool output): the tool, our own
 * one-line summary (for example {@code ok} or an error code), and how long it took.
 */
public record ToolEvent(String tool, String summary, Duration took) {
    public ToolEvent {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(took, "took");
        if (took.isNegative()) {
            throw new IllegalArgumentException("took must not be negative");
        }
    }
}
