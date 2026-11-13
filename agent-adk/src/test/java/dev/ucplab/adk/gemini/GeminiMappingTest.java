package dev.ucplab.adk.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.JsonSerializable;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** C15: 6.5's ports to a genai request and back, offline, against canned responses. */
class GeminiMappingTest {

    private final Map<String, Part> remembered = new LinkedHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Supplier<String> newId = () -> "ucplab-call-" + ids.incrementAndGet();

    private ModelTurn turn(String json) {
        return GeminiMapping.turn(GenerateContentResponse.fromJson(json), newId, remembered::put);
    }

    private static String response(String parts, String usage) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" + parts + "]},\"finishReason\":\"STOP\"}]"
                + (usage.isEmpty() ? "" : ",\"usageMetadata\":" + usage) + "}";
    }

    private static final String USAGE = "{\"promptTokenCount\":120,\"candidatesTokenCount\":15,\"totalTokenCount\":135}";

    // Covers: 6.5 (a text-only turn: its text, no tool calls, the usage)
    @Test
    void turn_textOnly_textNoCallsUsage() {
        ModelTurn t = turn(response("{\"text\":\"Which product \"},{\"text\":\"do you mean?\"}", USAGE));

        assertThat(t.text()).isEqualTo("Which product do you mean?");
        assertThat(t.toolCalls()).isEmpty();
        assertThat(t.usage()).isEqualTo(new Usage(120, 15));
        assertThat(remembered).isEmpty();
    }

    // Covers: 6.5, ADR 0004 item 2 (one call: the model's id kept, arguments in core's JSON value set, the part
    // remembered for its thought signature)
    @Test
    void turn_oneCall_idArgumentsAndPartRemembered() {
        ModelTurn t = turn(response("{\"functionCall\":{\"id\":\"m-1\",\"name\":\"search_catalog\",\"args\":"
                + "{\"query\":\"mug\",\"max_results\":5,\"ratio\":1.5}},\"thoughtSignature\":\"c2lnLTE=\"}", USAGE));

        assertThat(t.text()).isEmpty();
        assertThat(t.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("m-1");
            assertThat(c.name()).isEqualTo("search_catalog");
            assertThat(c.arguments()).containsEntry("query", "mug").containsEntry("max_results", 5L)
                    .containsEntry("ratio", new BigDecimal("1.5"));
        });
        assertThat(remembered).containsOnlyKeys("m-1");
        assertThat(remembered.get("m-1").thoughtSignature()).hasValueSatisfying(
                s -> assertThat(new String(s)).isEqualTo("sig-1"));
    }

    // Covers: 6.5, ADR 0004 item 2 (two calls in one turn, in order; without model ids each gets a fresh one of ours)
    @Test
    void turn_twoCalls_inOrderWithOurIds() {
        ModelTurn t = turn(response("{\"text\":\"Looking.\"},"
                + "{\"functionCall\":{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_123\"}}},"
                + "{\"functionCall\":{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_456\"}}}", USAGE));

        assertThat(t.text()).isEqualTo("Looking.");
        assertThat(t.toolCalls()).extracting(ToolCall::id).containsExactly("ucplab-call-1", "ucplab-call-2");
        assertThat(t.toolCalls()).extracting(c -> c.arguments().get("item_id"))
                .containsExactly("item_123", "item_456");
        assertThat(remembered).containsOnlyKeys("ucplab-call-1", "ucplab-call-2");
    }

    // Covers: 6.3 (thinking tokens count as output, as they are billed; thought parts are not the turn's text)
    @Test
    void turn_thinking_countedAsOutputAndNotText() {
        ModelTurn t = turn(response("{\"text\":\"(thinking about mugs)\",\"thought\":true},{\"text\":\"Sure.\"}",
                "{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"thoughtsTokenCount\":250,"
                        + "\"toolUsePromptTokenCount\":3}"));

        assertThat(t.text()).isEqualTo("Sure.");
        assertThat(t.usage()).isEqualTo(new Usage(103, 260));
    }

    // Covers: 6.3, R4 (a turn without usage metadata, or without a prompt count, fails the call rather than running
    // unmetered against the token budget and the live cost cap)
    @Test
    void turn_missingUsage_failsWithAFixedReason() {
        assertThatThrownBy(() -> turn(response("{\"text\":\"hi\"}", "")))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_USAGE))
                .hasMessage("the Gemini API returned no token counts");
        assertThatThrownBy(() -> turn(response("{\"text\":\"hi\"}", "{\"candidatesTokenCount\":3}")))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_USAGE));
        // A missing output count alone is zero output.
        assertThat(turn(response("{\"text\":\"hi\"}", "{\"promptTokenCount\":7}")).usage()).isEqualTo(new Usage(7, 0));
    }

    // Covers: 6.5, I8 (no candidate, a candidate with no content, or only thoughts: a fixed reason)
    @Test
    void turn_emptyCandidate_failsWithAFixedReason() {
        for (String json : List.of("{\"usageMetadata\":" + USAGE + "}",
                "{\"candidates\":[],\"usageMetadata\":" + USAGE + "}",
                "{\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":" + USAGE + "}",
                response("", USAGE),
                response("{\"text\":\"hmm\",\"thought\":true}", USAGE))) {
            assertThatThrownBy(() -> turn(json)).as(json).isInstanceOfSatisfying(GeminiException.class,
                    e -> assertThat(e.reason()).isEqualTo(Reason.EMPTY_RESPONSE));
        }
    }

    // Covers: 6.5, I8 (a blocked prompt or answer: a fixed reason, never the block message)
    @Test
    void turn_blocked_failsWithAFixedReason() {
        String blockedPrompt = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"blockReasonMessage\":\"secret detail\"},"
                + "\"usageMetadata\":" + USAGE + "}";
        String blockedAnswer = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"x\"}]},"
                + "\"finishReason\":\"SAFETY\"}],\"usageMetadata\":" + USAGE + "}";

        for (String json : List.of(blockedPrompt, blockedAnswer)) {
            assertThatThrownBy(() -> turn(json)).isInstanceOfSatisfying(GeminiException.class, e -> {
                assertThat(e.reason()).isEqualTo(Reason.BLOCKED);
                assertThat(e.getMessage()).isEqualTo("the Gemini API blocked the turn").doesNotContain("secret");
                assertThat(e.getCause()).isNull();
            });
        }
    }

    // Covers: 6.5 (a call with no name, or a malformed-call finish reason: a fixed reason)
    @Test
    void turn_malformedCall_failsWithAFixedReason() {
        String noName = response("{\"functionCall\":{\"args\":{}}}", USAGE);
        String malformed = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"x\"}]},"
                + "\"finishReason\":\"MALFORMED_FUNCTION_CALL\"}],\"usageMetadata\":" + USAGE + "}";

        for (String json : List.of(noName, malformed)) {
            assertThatThrownBy(() -> turn(json)).isInstanceOfSatisfying(GeminiException.class,
                    e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED_RESPONSE));
        }
    }

    // Covers: 6.5 (the config: system prompt, one declaration per tool with its schema as a map, automatic function
    // calling off, one candidate; the names read back from the built config, for I6)
    @Test
    void config_toolsAsDeclarations_namesReadBack() {
        List<ToolSpec> tools = List.of(
                new ToolSpec("ask_user", "Ask the user.", "{\"type\":\"object\",\"properties\":{\"question\":"
                        + "{\"type\":\"string\",\"minLength\":1,\"maxLength\":500}},\"required\":[\"question\"],"
                        + "\"additionalProperties\":false}"),
                new ToolSpec("get_product", "Look up.", "{\"type\":\"object\",\"properties\":{}}"));

        GenerateContentConfig config = GeminiMapping.config("SYSTEM PROMPT", tools, 8192);
        String json = JsonSerializable.toJsonString(config);

        assertThat(GeminiMapping.declaredNames(config)).containsExactly("ask_user", "get_product");
        assertThat(config.systemInstruction().map(Content::text)).hasValue("SYSTEM PROMPT");
        assertThat(config.automaticFunctionCalling().flatMap(a -> a.disable())).hasValue(true);
        assertThat(config.candidateCount()).hasValue(1);
        assertThat(config.maxOutputTokens()).hasValue(8192);
        assertThat(json).contains("\"parametersJsonSchema\"").contains("\"minLength\":1").contains("\"maxLength\":500")
                .contains("\"additionalProperties\":false");
        assertThat(GeminiMapping.declaredNames(GeminiMapping.config("p", List.of(), 1))).isEmpty();
    }

    // Covers: 6.5, ADR 0004 item 2 (the conversation as contents: user, model with its remembered call part, and the
    // step's results grouped in one user content, answered by name and by the model's id when it sent one)
    @Test
    void contents_conversation_rolesRememberedPartsAndGroupedResults() {
        turn(response("{\"functionCall\":{\"id\":\"m-1\",\"name\":\"get_product\",\"args\":{\"item_id\":\"a\"}},"
                + "\"thoughtSignature\":\"c2lnLTE=\"},"
                + "{\"functionCall\":{\"name\":\"get_product\",\"args\":{\"item_id\":\"b\"}}}", USAGE));
        Conversation c = new Conversation("p", List.of(
                new Message.User("one blue mug"),
                new Message.Model("", List.of(new ToolCall("m-1", "get_product", Map.of("item_id", "a")),
                        new ToolCall("ucplab-call-1", "get_product", Map.of("item_id", "b")))),
                new Message.ToolResult("m-1", "get_product", "{\"ok\":true}"),
                new Message.ToolResult("ucplab-call-1", "get_product", "{\"ok\":false}"),
                new Message.Model("Done.", List.of())));

        List<Content> contents = GeminiMapping.contents(c, id -> Optional.ofNullable(remembered.get(id)));

        assertThat(contents).extracting(x -> x.role().orElse("")).containsExactly("user", "model", "user", "model");
        List<Part> model = contents.get(1).parts().orElseThrow();
        assertThat(model.get(0).thoughtSignature()).hasValueSatisfying(s -> assertThat(new String(s)).isEqualTo("sig-1"));
        assertThat(model.get(0).functionCall().flatMap(f -> f.id())).hasValue("m-1");
        List<Part> results = contents.get(2).parts().orElseThrow();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).functionResponse().flatMap(f -> f.id())).hasValue("m-1");
        assertThat(results.get(0).functionResponse().flatMap(f -> f.name())).hasValue("get_product");
        assertThat(results.get(0).functionResponse().flatMap(f -> f.response())).hasValue(Map.of("ok", true));
        // Our own ids never reach the API.
        assertThat(results.get(1).functionResponse().flatMap(f -> f.id())).isEmpty();
        assertThat(contents.get(3).text()).isEqualTo("Done.");
    }

    // Covers: 6.5 (a model turn this client never received, as in a replayed conversation, is sent as a plain call)
    @Test
    void contents_unrememberedCall_sentAsAPlainCall() {
        Conversation c = new Conversation("p", List.of(new Message.User("hi"),
                new Message.Model("", List.of(new ToolCall("x-1", "ask_user", Map.of("question", "Which?"))))));

        List<Content> contents = GeminiMapping.contents(c, id -> Optional.empty());

        Part call = contents.get(1).parts().orElseThrow().getFirst();
        assertThat(call.functionCall().flatMap(f -> f.name())).hasValue("ask_user");
        assertThat(call.thoughtSignature()).isEmpty();
    }
}
