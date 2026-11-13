package dev.ucplab.core.agent;

import java.util.List;
import java.util.Objects;

/** What a model turn is generated from (6.5, D2): the system prompt and the messages so far, oldest first. */
public record Conversation(String systemPrompt, List<Message> messages) {
    public Conversation {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    /** This conversation with one more message at the end. */
    public Conversation append(Message message) {
        Objects.requireNonNull(message, "message");
        List<Message> next = new java.util.ArrayList<>(messages);
        next.add(message);
        return new Conversation(systemPrompt, next);
    }
}
