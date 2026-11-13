package dev.ucplab.core.agent;

import java.util.List;
import java.util.Objects;

/** One message of a {@link Conversation} (6.5, D2). Text is never null; an empty message is {@code ""}. */
public sealed interface Message {

    /** What the user said. */
    record User(String text) implements Message {
        public User {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A model turn as it is replayed to the model: its text and the tool calls it asked for. */
    record Model(String text, List<ToolCall> toolCalls) implements Message {
        public Model {
            Objects.requireNonNull(text, "text");
            toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        }
    }

    /**
     * The result of one tool call, as canonical JSON. Merchant and user text in it is already wrapped as untrusted
     * (6.4); it never holds an approval token or a payment credential (I6, I8).
     */
    record ToolResult(String callId, String tool, String json) implements Message {
        public ToolResult {
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(json, "json");
            if (tool.isBlank()) {
                throw new IllegalArgumentException("tool must not be blank");
            }
        }
    }
}
