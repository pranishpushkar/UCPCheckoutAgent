package dev.ucplab.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What every offline {@link ModelClient} must do (6.5 [CONTRACT], I6): a turn has text that is never null, tool calls in
 * order, each with an id and a name the model was offered and the arguments it asked for, and usage; a client that cannot
 * answer fails with an exception, never with a null or a half-built turn. The live Gemini client is not run here: it needs
 * a key and a network, and is tested live only. A subclass sets each situation up through {@link #scenario(Situation)}
 * and may skip one with {@code Assumptions.abort(reason)}.
 */
public abstract class ModelClientContract {

    public enum Situation {
        /** The user has just spoken: the model asks for a tool. */
        CALLS_A_TOOL,
        /** A tool result has come back and the model has nothing more to ask: it answers in words. */
        ANSWERS_WITH_TEXT,
        /** The client cannot answer this conversation. */
        CANNOT_ANSWER
    }

    /** What a client is asked and what it must answer. {@code expectedTool} and {@code expectedArgs} are for CALLS_A_TOOL. */
    public record Scenario(
            ModelClient client, Conversation conversation, List<ToolSpec> tools, String expectedTool,
            Map<String, Object> expectedArgs) {}

    protected static ToolSpec tool(String name) {
        return new ToolSpec(name, "the " + name + " tool", "{\"type\":\"object\"}");
    }

    protected abstract Scenario scenario(Situation situation);

    // Covers: 6.5, I6 (a turn that asks for a tool has an id, the name of a tool it was offered, and the arguments it was
    // scripted or built with; its text is not null, and its usage is not negative)
    @Test
    void generate_whenItAsksForATool_returnsThatToolCallWithItsArguments() {
        Scenario s = scenario(Situation.CALLS_A_TOOL);

        ModelTurn turn = s.client().generate(s.conversation(), s.tools());

        assertThat(turn.text()).isNotNull();
        assertThat(turn.usage().total()).isNotNegative();
        assertThat(turn.toolCalls()).hasSize(1);
        ToolCall call = turn.toolCalls().get(0);
        assertThat(call.id()).isNotBlank();
        assertThat(call.name()).isEqualTo(s.expectedTool());
        assertThat(s.tools()).extracting(ToolSpec::name).contains(call.name());
        assertThat(call.arguments()).containsAllEntriesOf(s.expectedArgs());
    }

    // Covers: 6.5 (the ids of one turn's calls are different, so each result can be matched to its call)
    @Test
    void generate_callIdsOfATurn_areDistinct() {
        Scenario s = scenario(Situation.CALLS_A_TOOL);

        ModelTurn turn = s.client().generate(s.conversation(), s.tools());

        assertThat(new HashSet<>(turn.toolCalls().stream().map(ToolCall::id).toList())).hasSameSizeAs(turn.toolCalls());
    }

    // Covers: 6.5 (a turn that answers in words has text and no tool call)
    @Test
    void generate_afterAToolResult_canAnswerInWordsWithNoToolCall() {
        Scenario s = scenario(Situation.ANSWERS_WITH_TEXT);

        ModelTurn turn = s.client().generate(s.conversation(), s.tools());

        assertThat(turn.text()).isNotBlank();
        assertThat(turn.toolCalls()).isEmpty();
    }

    // Covers: 6.5, 11 (a client that cannot answer throws: never a null turn. A failure that carries a code, as
    // ModelFailure says, has a fixed identifier for it, never a message)
    @Test
    void generate_whenItCannotAnswer_throws_andACodeIsAnIdentifier() {
        Scenario s = scenario(Situation.CANNOT_ANSWER);

        assertThatThrownBy(() -> s.client().generate(s.conversation(), s.tools())).isInstanceOfSatisfying(
                RuntimeException.class, e -> {
                    if (e instanceof ModelFailure f) {
                        assertThat(f.code()).matches(Pattern.compile("[A-Z][A-Z0-9_]*"));
                    }
                });
    }
}
