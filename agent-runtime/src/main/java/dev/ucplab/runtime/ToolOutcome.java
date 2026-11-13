package dev.ucplab.runtime;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.json.CanonicalJson;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a tool call gives the run: the canonical JSON the model receives, and a code for the run's tool events and trace
 * ({@code ok} or one of our error codes, never model or merchant text).
 */
public sealed interface ToolOutcome {

    String json();

    String code();

    /** A result, or an error the model may react to. {@code intent} is the intent a {@code propose_purchase} made. */
    record Result(String json, String code, Optional<IntentId> intent) implements ToolOutcome {
        public Result {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(intent, "intent");
        }
    }

    /** {@code ask_user} timed out: the run ends as {@code user_timeout}, with no further tool call (6.2). */
    record NoAnswer() implements ToolOutcome {
        @Override
        public String json() {
            return CanonicalJson.write(Map.of("error", "no_answer"));
        }

        @Override
        public String code() {
            return "no_answer";
        }
    }

    static ToolOutcome ok(Map<String, Object> result) {
        return new Result(CanonicalJson.write(result), "ok", Optional.empty());
    }

    /** {@code {"error":code}}: {@code not_found} or {@code unavailable}. */
    static ToolOutcome error(String code) {
        return new Result(CanonicalJson.write(Map.of("error", code)), code, Optional.empty());
    }

    /** {@code {"error":"invalid_argument","detail":...}}, with our fixed wording as the detail. */
    static ToolOutcome invalid(String detail) {
        return new Result(CanonicalJson.write(Map.of("error", "invalid_argument", "detail", detail)),
                "invalid_argument", Optional.empty());
    }
}
