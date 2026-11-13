package dev.ucplab.runtime;

import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ask_user} (6.2): {@code question} (1 to 500 characters). Blocks on the {@link UserChannel} for up to the
 * timeout (5 minutes by default) and returns {@code {answer}}, wrapped as untrusted user text. No answer in time is a
 * {@link ToolOutcome.NoAnswer}, which ends the run as {@code user_timeout}.
 */
final class AskUserTool implements Tool {

    static final String NAME = "ask_user";
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final ToolSpec SPEC = new ToolSpec(NAME,
            "Asks the user a question and waits for the answer. Use it when the request is ambiguous.",
            CanonicalJson.write(Map.of(
                    "type", "object",
                    "properties", Map.of("question", Map.of("type", "string", "minLength", 1, "maxLength", 500,
                            "description", "The question for the user.")),
                    "required", List.of("question"),
                    "additionalProperties", false)));

    private final UserChannel user;
    private final Duration timeout;

    AskUserTool(UserChannel user, Duration timeout) {
        this.user = Objects.requireNonNull(user, "user");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ToolOutcome run(ToolContext context, Map<String, Object> arguments) {
        String question;
        try {
            Args.only(arguments, Set.of("question"));
            question = Args.text(arguments, "question", 1, 500);
        } catch (Args.Invalid e) {
            return ToolOutcome.invalid(e.getMessage());
        }
        Optional<String> answer = user.ask(question, timeout);
        if (answer.isEmpty()) {
            return new ToolOutcome.NoAnswer();
        }
        return ToolOutcome.ok(Map.of("answer", UntrustedText.wrap("user", "answer", answer.get())));
    }
}
