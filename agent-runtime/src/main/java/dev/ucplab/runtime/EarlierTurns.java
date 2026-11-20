package dev.ucplab.runtime;

import dev.ucplab.core.agent.Message;
import java.util.List;

/**
 * 6.3 [CONTRACT] earlier turns in the context: what a continuation sends the model of the turns before it. User
 * messages, the agent's texts and its tool calls stay as they were; each earlier tool result becomes a one-line summary
 * of ours, {@code {"earlier": "<summary>"}}, except an {@code ask_user} answer, which is the user's own words.
 */
final class EarlierTurns {

    private EarlierTurns() {
    }

    /** The earlier turns' messages, each tool result summarised. */
    static List<Message> compact(List<Message> messages) {
        return List.copyOf(messages); // Skeleton (red).
    }
}
