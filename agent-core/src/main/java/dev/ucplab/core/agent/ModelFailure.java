package dev.ucplab.core.agent;

import java.time.Duration;
import java.util.Optional;

/**
 * A model call's failure that carries a fixed code (11): what the trace's {@code llm.call} line records for a call that
 * threw, so a server error, a timeout and a dropped connection can be told apart. Implemented by the exceptions a
 * {@link ModelClient} throws. The code is a short identifier such as {@code TIMEOUT}, never a message, a URL or text
 * from the model.
 */
public interface ModelFailure {

    /** The failure's fixed code. */
    String code();

    /**
     * The failure's cause from 6.5's closed vocabulary, such as {@code http_429} or {@code finish_SAFETY}: a status, an
     * enum name or a class's simple name, never content (I8). By default, the code.
     */
    default String cause() {
        return code();
    }

    /** 6.3: whether one more try may succeed (HTTP 429, 503 or 504, or the client's own timeout). */
    default boolean retryable() {
        return false;
    }

    /** 6.3: how long the API asked us to wait before trying again (Gemini's {@code retryDelay}), when it said. */
    default Optional<Duration> retryAfter() {
        return Optional.empty();
    }
}
