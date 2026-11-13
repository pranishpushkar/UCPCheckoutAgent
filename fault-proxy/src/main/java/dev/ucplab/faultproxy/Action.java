package dev.ucplab.faultproxy;

import java.util.Objects;
import java.util.OptionalInt;

/** The six fault actions of §12, with the parameters of §12 "Action parameters". */
public sealed interface Action {

    /** The wire name, as in the rule JSON and the log's {@code action} field. */
    String type();

    /** Wait {@code ms}, then forward. */
    record Delay(long ms) implements Action {
        public Delay {
            requireNonNegative(ms, "delay.ms");
        }

        @Override
        public String type() {
            return "delay";
        }
    }

    /**
     * Answer without contacting the merchant: either with {@code status} (plus {@code Retry-After} when
     * {@code retryAfterSeconds} is set) or by closing the connection. Exactly one of {@code status} and {@code close}.
     */
    record FailBeforeForward(OptionalInt status, boolean close, OptionalInt retryAfterSeconds) implements Action {
        public FailBeforeForward {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(retryAfterSeconds, "retryAfterSeconds");
            if (status.isPresent() == close) {
                throw new IllegalArgumentException("fail_before_forward needs exactly one of status or close");
            }
            if (status.isPresent() && (status.getAsInt() < 200 || status.getAsInt() > 599)) {
                throw new IllegalArgumentException("fail_before_forward.status must be 200 to 599: " + status.getAsInt());
            }
            if (retryAfterSeconds.isPresent()) {
                if (close) {
                    throw new IllegalArgumentException("fail_before_forward.retryAfterSeconds needs a status");
                }
                requireNonNegative(retryAfterSeconds.getAsInt(), "fail_before_forward.retryAfterSeconds");
            }
        }

        public static FailBeforeForward status(int status) {
            return new FailBeforeForward(OptionalInt.of(status), false, OptionalInt.empty());
        }

        public static FailBeforeForward closeConnection() {
            return new FailBeforeForward(OptionalInt.empty(), true, OptionalInt.empty());
        }

        @Override
        public String type() {
            return "fail_before_forward";
        }
    }

    /** Forward, read the merchant's whole reply, then close the client connection without answering. */
    record ForwardThenDropResponse() implements Action {
        @Override
        public String type() {
            return "forward_then_drop_response";
        }
    }

    /** Forward, then hold the merchant's reply for {@code ms} before sending it. */
    record ForwardThenDelayResponse(long ms) implements Action {
        public ForwardThenDelayResponse {
            requireNonNegative(ms, "forward_then_delay_response.ms");
        }

        @Override
        public String type() {
            return "forward_then_delay_response";
        }
    }

    /** Send the request to the merchant twice with the same headers and answer with the first copy's reply. */
    record DuplicateRequest(Mode mode) implements Action {
        public enum Mode { PARALLEL, SEQUENTIAL }

        public DuplicateRequest {
            Objects.requireNonNull(mode, "mode");
        }

        @Override
        public String type() {
            return "duplicate_request";
        }
    }

    /** Forward, then truncate or garble the reply body. */
    record CorruptResponse(Kind kind) implements Action {
        public enum Kind { TRUNCATE, GARBLE }

        public CorruptResponse {
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String type() {
            return "corrupt_response";
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }
}
