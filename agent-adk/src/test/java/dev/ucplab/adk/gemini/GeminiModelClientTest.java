package dev.ucplab.adk.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.runtime.ModelClients;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** C15: {@link GeminiModelClient} against a loopback stub of the Gemini API. */
class GeminiModelClientTest {

    private static final String KEY = "canary-00112233445566778899aabbccddeeff";
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("get_product", "Look up a product.", "{\"type\":\"object\",\"properties\":{\"item_id\":"
                    + "{\"type\":\"string\"}},\"required\":[\"item_id\"]}"),
            new ToolSpec("ask_user", "Ask the user.", "{\"type\":\"object\",\"properties\":{\"question\":"
                    + "{\"type\":\"string\"}},\"required\":[\"question\"]}"));
    private static final String USAGE = "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":5,"
            + "\"thoughtsTokenCount\":20}";

    private final ConcurrentLinkedQueue<List<String>> requested = new ConcurrentLinkedQueue<>();
    private GeminiStub stub;

    @BeforeEach
    void start() throws Exception {
        stub = new GeminiStub();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private ModelClientSettings settings() {
        return ModelClientSettings.defaults().withModel("stub-model").withBaseUrl(stub.url())
                .withCallTimeout(Duration.ofSeconds(5)).withRequestedTools(requested::add);
    }

    private GeminiModelClient client() {
        return new GeminiModelClient(ModelKey.of(KEY), settings(), name -> null);
    }

    private static Conversation ask(String text) {
        return new Conversation("You are a shopping agent.", List.of(new Message.User(text)));
    }

    // Covers: 6.5, R2 (one call per turn to the pinned path; the turn's call and usage, thinking counted as output)
    @Test
    void generate_oneCall_turnFromTheResponse() {
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                + "{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_123\"}}}]},\"finishReason\":\"STOP\"}],"
                + USAGE + "}");

        ModelTurn turn = client().generate(ask("one blue mug"), TOOLS);

        assertThat(turn.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("get_product");
            assertThat(c.arguments()).isEqualTo(Map.of("item_id", "item_123"));
        });
        assertThat(turn.usage()).isEqualTo(new Usage(50, 25));
        assertThat(stub.received()).singleElement().satisfies(r -> {
            assertThat(r.uri()).isEqualTo("/v1beta/models/stub-model:generateContent");
            assertThat(r.body()).contains("\"systemInstruction\"").contains("You are a shopping agent.");
        });
    }

    // Covers: I6 (the names given to the observer are those of the request the client built, on every call)
    @Test
    void generate_everyCall_reportsTheNamesOfTheBuiltRequest() {
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}}],"
                + USAGE + "}");
        GeminiModelClient client = client();

        client.generate(ask("a"), TOOLS);
        client.generate(ask("b"), List.of());

        assertThat(requested).containsExactly(List.of("get_product", "ask_user"), List.of());
        assertThat(stub.received().getFirst().body()).contains("\"get_product\"").contains("\"ask_user\"");
        assertThat(stub.received().get(1).body()).doesNotContain("functionDeclarations");
    }

    // Covers: R4 (the answering model of each call goes to the settings' observer; the turn itself is unchanged, 6.5)
    @Test
    void generate_everyCall_reportsTheAnsweringModel() {
        ConcurrentLinkedQueue<String> answering = new ConcurrentLinkedQueue<>();
        GeminiModelClient client = new GeminiModelClient(ModelKey.of(KEY),
                settings().withAnsweringModel(answering::add), name -> null);
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},"
                + "\"finishReason\":\"STOP\"}]," + USAGE + ",\"modelVersion\":\"gemini-3.6-flash\"}");

        client.generate(ask("hello"), TOOLS);
        client.generate(ask("hello again"), TOOLS);

        assertThat(answering).containsExactly("gemini-3.6-flash", "gemini-3.6-flash");
    }

    // Covers: I8, R2 (the key travels in the x-goog-api-key header only: never in the URL or the body)
    @Test
    void generate_key_inTheHeaderOnly() {
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}}],"
                + USAGE + "}");

        client().generate(ask("hi"), TOOLS);

        assertThat(stub.received()).singleElement().satisfies(r -> {
            assertThat(r.keyHeader()).isEqualTo(KEY);
            assertThat(r.uri()).doesNotContain(KEY).doesNotContain("key=");
            assertThat(r.body()).doesNotContain(KEY);
        });
    }

    // Covers: 6.5 (Gemini 3 thought signatures: the call part comes back on the next request exactly as received)
    @Test
    void generate_nextRequest_sendsTheThoughtSignatureBack() {
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
                + "{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_123\"}},\"thoughtSignature\":\"c2lnLTE=\"}]}}],"
                + USAGE + "}");
        GeminiModelClient client = client();
        Conversation first = ask("one blue mug");
        ModelTurn turn = client.generate(first, TOOLS);

        Conversation second = first.append(new Message.Model(turn.text(), turn.toolCalls()))
                .append(new Message.ToolResult(turn.toolCalls().getFirst().id(), "get_product", "{\"title\":\"x\"}"));
        client.generate(second, TOOLS);

        assertThat(stub.received().get(1).body()).contains("\"thoughtSignature\":\"c2lnLTE=\"")
                .contains("\"functionResponse\"");
    }

    // Covers: 6.3, I8 (each HTTP failure maps to its fixed reason; one attempt each, no retry)
    @Test
    void generate_httpErrors_fixedReasonsOneAttempt() {
        Map<Integer, Reason> cases = Map.of(400, Reason.BAD_REQUEST, 401, Reason.UNAUTHORIZED, 403,
                Reason.UNAUTHORIZED, 429, Reason.RATE_LIMITED, 500, Reason.SERVER_ERROR, 503, Reason.SERVER_ERROR);
        int sent = 0;
        for (Map.Entry<Integer, Reason> c : cases.entrySet()) {
            stub.answer(c.getKey(), "{\"error\":{\"code\":" + c.getKey() + ",\"message\":\"no\"}}");
            GeminiModelClient client = client();
            assertThatThrownBy(() -> client.generate(ask("hi"), TOOLS)).as("HTTP %d", c.getKey())
                    .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.reason()).isEqualTo(c.getValue()));
            sent++;
            assertThat(stub.received()).as("one attempt for HTTP %d", c.getKey()).hasSize(sent);
        }
    }

    // Covers: ADR 0004 item 4 (the client refuses to start when GOOGLE_GENAI_CLIENT_MODE is set)
    @Test
    void construct_clientModeSet_refused() {
        assertThatThrownBy(() -> new GeminiModelClient(ModelKey.of(KEY), settings(),
                name -> name.equals(GeminiModelClient.CLIENT_MODE) ? "replay" : null))
                .isInstanceOfSatisfying(GeminiException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONFIGURATION));
    }

    // Covers: R4, Decision 1 (the provider is found through ServiceLoader under "gemini", and builds this client)
    @Test
    void modelClients_findTheGeminiProvider() {
        ModelClient client = ModelClients.load(GeminiProvider.NAME, ModelKey.of(KEY), settings());

        assertThat(ModelClients.available(GeminiProvider.NAME)).isTrue();
        assertThat(client).isInstanceOf(GeminiModelClient.class);
        assertThat(client.toString()).isEqualTo("GeminiModelClient[stub-model]");
        ((GeminiModelClient) client).close();
    }

    // Covers: 6.5 (the pinned model when the settings name none)
    @Test
    void defaults_pinnedModel() {
        GeminiModelClient client = new GeminiModelClient(ModelKey.of(KEY),
                ModelClientSettings.defaults().withBaseUrl(stub.url()), name -> null);

        assertThat(client.toString()).isEqualTo("GeminiModelClient[gemini-3.8-flash]");
        client.close();
    }
}
