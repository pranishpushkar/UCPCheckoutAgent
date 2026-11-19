package dev.ucplab.adk.gemini;

import dev.ucplab.core.agent.ModelFailure;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Every failure that leaves {@link GeminiModelClient} (I8, ADR 0004): one of our fixed {@link Reason}s, never the
 * SDK's message, a URL, a header or a response body. It has no cause and no suppressed exceptions, so a logged stack
 * trace cannot carry them either. As a {@link ModelFailure}, its code is the reason's name.
 */
public final class GeminiException extends RuntimeException implements ModelFailure {

    private static final long serialVersionUID = 1L;

    /** Why a model call failed. Each reason's message is fixed. */
    public enum Reason {
        /** The client could not be configured (an environment override such as GOOGLE_GENAI_CLIENT_MODE is set). */
        CONFIGURATION("the Gemini client could not be configured"),
        /** HTTP 401 or 403: the key was refused. */
        UNAUTHORIZED("the Gemini API refused the key"),
        /** HTTP 429. */
        RATE_LIMITED("the Gemini API rate-limited the call"),
        /** Another HTTP 4xx. */
        BAD_REQUEST("the Gemini API refused the request"),
        /** HTTP 5xx. */
        SERVER_ERROR("the Gemini API failed"),
        /** The per-call timeout passed. */
        TIMEOUT("the Gemini call timed out"),
        /** The connection failed or dropped. */
        NETWORK("the Gemini call failed on the network"),
        /** The prompt or the answer was blocked (promptFeedback or a safety finish reason). */
        BLOCKED("the Gemini API blocked the turn"),
        /** No candidate, or a candidate with no content. */
        EMPTY_RESPONSE("the Gemini API returned no answer"),
        /** A function call without a name, malformed arguments, or a malformed-call finish reason. */
        MALFORMED_RESPONSE("the Gemini API returned a malformed answer"),
        /** No usage metadata, so the turn's tokens cannot be counted against the budget (6.3). */
        MISSING_USAGE("the Gemini API returned no token counts"),
        /** Anything else. */
        INTERNAL("the Gemini call failed");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;
    private final String cause;
    private final Optional<Duration> retryAfter;

    /** A failure whose cause (6.5) is the reason's name in lower case, such as {@code configuration}. */
    GeminiException(Reason reason) {
        this(reason, Objects.requireNonNull(reason, "reason").name().toLowerCase(java.util.Locale.ROOT), Optional.empty());
    }

    /** A failure of the answer, whose cause is {@code cause} (6.5): one of {@link GeminiMapping}'s constants. */
    GeminiException(Reason reason, String cause) {
        this(reason, cause, Optional.empty());
    }

    /**
     * @param cause 6.5's cause: a status, an enum name or a class's simple name, never content (I8)
     * @param retryAfter the API's {@code Retry-After}, when it sent one as a number of seconds
     */
    GeminiException(Reason reason, String cause, Optional<Duration> retryAfter) {
        super(Objects.requireNonNull(reason, "reason").message, null, false, true);
        this.reason = reason;
        this.cause = Objects.requireNonNull(cause, "cause");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Reason reason() {
        return reason;
    }

    /** The reason's name, such as {@code TIMEOUT} or {@code SERVER_ERROR}, for the trace's {@code llm.call} line. */
    @Override
    public String code() {
        return reason.name();
    }

    @Override
    public String cause() {
        return cause;
    }

    /** 6.3: only an HTTP 429 or 503 may succeed when tried again. */
    @Override
    public boolean retryable() {
        return cause.equals("http_429") || cause.equals("http_503");
    }

    @Override
    public Optional<Duration> retryAfter() {
        return retryAfter;
    }
}
