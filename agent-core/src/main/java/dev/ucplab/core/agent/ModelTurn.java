package dev.ucplab.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * One model turn (6.5 [CONTRACT]): the model's text, the tool calls it asks for, in order, and what the turn used.
 * {@code text} is never null; a turn with no text is {@code ""}.
 */
public record ModelTurn(String text, List<ToolCall> toolCalls, Usage usage) {
    public ModelTurn {
        Objects.requireNonNull(text, "text");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(usage, "usage");
    }
}
