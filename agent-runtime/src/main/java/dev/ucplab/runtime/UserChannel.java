package dev.ucplab.runtime;

import java.time.Duration;
import java.util.Optional;

/**
 * How {@code ask_user} reaches the user (6.2). {@link #ask} blocks until the user answers or {@code timeout} passes;
 * empty means no answer in time. The scripted harness supplies its own; M6 completes the wait from
 * {@code POST /api/runs/{id}/messages}.
 */
public interface UserChannel {

    Optional<String> ask(String question, Duration timeout);
}
