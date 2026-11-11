package dev.ucplab.core.merchant;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of one merchant call (Appendix B, 8.1). {@code NotSent} means the request provably never left;
 * {@code Uncertain} means it may have been processed, which sends the intent to reconciliation (7.5).
 */
public sealed interface CallOutcome<T> {

    record Ok<T>(int httpStatus, T value, List<MerchantMessage> messages) implements CallOutcome<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    record NotSent<T>(String cause) implements CallOutcome<T> {
        public NotSent {
            Objects.requireNonNull(cause, "cause");
        }
    }

    record Uncertain<T>(String cause) implements CallOutcome<T> {
        public Uncertain {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * Valid only for create: every line was unavailable, so the merchant answered 200 with {@code ucp.status} error and
     * no session id (8.1, 7.2 {@code create_rejected}). No session exists, so nothing is resent or cancelled.
     */
    record Rejected<T>(int httpStatus, List<MerchantMessage> messages) implements CallOutcome<T> {
        public Rejected {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    record ProtocolError<T>(int httpStatus, String code, String content, Optional<Duration> retryAfter)
            implements CallOutcome<T> {
        public ProtocolError {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(retryAfter, "retryAfter");
        }
    }
}
