package dev.ucplab.service.web;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * An API refusal, rendered by {@link ApiErrors} as {@code {code, message, request_id}} (23.5). The message is one of
 * the fixed texts the caller chose, never input or another exception's text.
 */
public final class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String publicMessage;
    private final Optional<Duration> retryAfter;

    private ApiException(ErrorCode code, String publicMessage, Optional<Duration> retryAfter) {
        super(code.code(), null, false, false);
        this.code = Objects.requireNonNull(code, "code");
        this.publicMessage = Objects.requireNonNull(publicMessage, "publicMessage");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public static ApiException of(ErrorCode code) {
        return new ApiException(code, code.message(), Optional.empty());
    }

    /** With a fixed message of the caller's own; it must never be built from input. */
    public static ApiException of(ErrorCode code, String fixedMessage) {
        return new ApiException(code, fixedMessage, Optional.empty());
    }

    /** 409 {@code intent_busy} with {@code Retry-After} (23.5). */
    public static ApiException busy(Duration retryAfter) {
        return new ApiException(ErrorCode.INTENT_BUSY, ErrorCode.INTENT_BUSY.message(), Optional.of(retryAfter));
    }

    public ErrorCode code() {
        return code;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public Optional<Duration> retryAfter() {
        return retryAfter;
    }
}
