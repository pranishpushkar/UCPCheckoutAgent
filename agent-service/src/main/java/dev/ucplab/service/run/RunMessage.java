package dev.ucplab.service.run;

import java.time.Instant;
import java.util.Objects;

/** One user-visible message of a run (23.1): what the user said, an {@code ask_user} question, or the agent's text. */
public record RunMessage(String role, String kind, String text, Instant at) {

    public static final String USER = "user";
    public static final String AGENT = "agent";
    public static final String MESSAGE = "message";
    public static final String QUESTION = "question";

    public RunMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(at, "at");
    }
}
