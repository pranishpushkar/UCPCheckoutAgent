package dev.ucplab.runtime;

import dev.ucplab.core.agent.Conversation;
import java.util.Objects;

/**
 * How a run ended and the conversation it ended with, so the service can keep it in memory and continue it with
 * {@link AgentRuntime#resume} (23.1, m6-plan Decision 3). The conversation is whatever the model saw: the system
 * prompt, the messages and the tool results. It is never stored (21.3): the caller holds it in instance memory only.
 */
public record RunSession(RunOutcome outcome, Conversation conversation) {
    public RunSession {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(conversation, "conversation");
    }
}
