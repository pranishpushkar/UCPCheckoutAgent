package dev.ucplab.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentPortRecordsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ToolCall CALL = new ToolCall("c1", "search_catalog", Map.of("query", "mug"));

    // Covers: 6.5 (a turn's text is never null; a turn with only tool calls has text "")
    @Test
    void modelTurn_nullText_isRefusedAndEmptyTextIsTheEmptyTurn() {
        assertThatThrownBy(() -> new ModelTurn(null, List.of(), Usage.NONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelTurn("", null, Usage.NONE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ModelTurn("", List.of(), null)).isInstanceOf(NullPointerException.class);

        ModelTurn turn = new ModelTurn("", List.of(CALL), new Usage(10, 2));
        assertThat(turn.text()).isEmpty();
        assertThat(turn.toolCalls()).containsExactly(CALL);
    }

    // Covers: 6.5, D2 (a turn's tool calls are a copy: changing the caller's list later changes nothing)
    @Test
    void modelTurn_toolCalls_areCopied() {
        List<ToolCall> calls = new ArrayList<>(List.of(CALL));
        ModelTurn turn = new ModelTurn("", calls, Usage.NONE);
        calls.clear();

        assertThat(turn.toolCalls()).containsExactly(CALL);
        assertThatThrownBy(() -> turn.toolCalls().add(CALL)).isInstanceOf(UnsupportedOperationException.class);
    }

    // Covers: D2 (no nulls in a conversation or its messages; "" for an empty message)
    @Test
    void conversationAndMessages_nulls_areRefused() {
        assertThatThrownBy(() -> new Conversation(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Conversation("p", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.User(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.Model(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.Model("", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.ToolResult(null, "t", "{}")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.ToolResult("c1", null, "{}")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.ToolResult("c1", "t", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message.ToolResult("c1", " ", "{}"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new Message.User("").text()).isEmpty();
        assertThat(new Message.Model("", List.of()).text()).isEmpty();
    }

    // Covers: D2 (append returns a new conversation; the original is unchanged)
    @Test
    void conversation_append_leavesTheOriginalUnchanged() {
        Conversation first = new Conversation("prompt", List.of(new Message.User("hi")));
        Conversation second = first.append(new Message.Model("", List.of(CALL)));

        assertThat(first.messages()).hasSize(1);
        assertThat(second.messages()).containsExactly(new Message.User("hi"), new Message.Model("", List.of(CALL)));
        assertThat(second.systemPrompt()).isEqualTo("prompt");
    }

    // Covers: 6.2, D2 (a tool spec needs a name; description and schema are never null)
    @Test
    void toolSpec_blankNameOrNulls_areRefused() {
        assertThatThrownBy(() -> new ToolSpec(" ", "d", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolSpec("t", null, "{}")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolSpec("t", "d", null)).isInstanceOf(NullPointerException.class);
    }

    // Covers: D2 (a tool call needs an id and a name; its arguments are an unmodifiable copy that keeps JSON nulls and
    // the model's order)
    @Test
    void toolCall_arguments_areAnOrderedUnmodifiableCopyKeepingNulls() {
        assertThatThrownBy(() -> new ToolCall(" ", "t", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall("c1", " ", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall("c1", "t", null)).isInstanceOf(NullPointerException.class);

        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("z", 1);
        args.put("a", null);
        ToolCall call = new ToolCall("c1", "t", args);
        args.put("late", true);

        assertThat(call.arguments()).containsOnlyKeys("z", "a");
        assertThat(call.arguments().keySet()).containsExactly("z", "a");
        assertThat(call.arguments().get("a")).isNull();
        assertThatThrownBy(() -> call.arguments().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    // Covers: 6.3, D2 (token counts are never negative; plus and total add them)
    @Test
    void usage_negative_isRefusedAndPlusAdds() {
        assertThatThrownBy(() -> new Usage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, -1)).isInstanceOf(IllegalArgumentException.class);

        Usage sum = new Usage(10, 2).plus(new Usage(5, 1));
        assertThat(sum).isEqualTo(new Usage(15, 3));
        assertThat(sum.total()).isEqualTo(18);
        assertThat(Usage.NONE.total()).isZero();
    }

    // Covers: section 10 agent_run (a run id is never blank)
    @Test
    void runId_blank_isRefused() {
        assertThatThrownBy(() -> new RunId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunId(null)).isInstanceOf(NullPointerException.class);
        assertThat(new RunId("r1")).hasToString("r1");
    }

    // Covers: 23.1 (a tool event's duration is never negative)
    @Test
    void toolEvent_negativeDuration_isRefused() {
        assertThatThrownBy(() -> new ToolEvent("t", "ok", Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolEvent("t", null, Duration.ZERO)).isInstanceOf(NullPointerException.class);
    }

    // Covers: section 10 agent_run, 23.1 (a run has no nulls, no negative counts, and an outcome exactly when it has
    // ended, not before it started)
    @Test
    void runRecord_invalidFields_areRefused() {
        UserId alice = new UserId("alice");
        RunId id = new RunId("r1");
        RunRecord started = RunRecord.started(id, alice, "scripted", T0);
        assertThat(started.endedAt()).isEmpty();
        assertThat(started.outcome()).isEmpty();

        assertThatThrownBy(() -> RunRecord.started(id, null, "scripted", T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RunRecord(id, alice, "m", T0, Optional.empty(), Optional.empty(), -1, 0, 0, 0,
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunRecord(id, alice, "m", T0, Optional.of(T0.minusSeconds(1)),
                Optional.of("done"), 0, 0, 0, 0, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunRecord(id, alice, "m", T0, Optional.of(T0), Optional.empty(), 0, 0, 0, 0,
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunRecord(id, alice, "m", T0, Optional.empty(), Optional.of("done"), 0, 0, 0, 0,
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunRecord(id, alice, "m", T0, Optional.empty(), Optional.empty(), 0, 0, 0, 0,
                null, List.of())).isInstanceOf(NullPointerException.class);
    }

    // Covers: D2 (a map with a null key cannot be copied into a tool call's arguments)
    @Test
    void toolCall_nullKey_isRefused() {
        Map<String, Object> args = new HashMap<>();
        args.put(null, 1);
        assertThatThrownBy(() -> new ToolCall("c1", "t", args)).isInstanceOf(IllegalArgumentException.class);
    }
}
