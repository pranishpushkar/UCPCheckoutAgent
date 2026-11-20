package dev.ucplab.adk.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelFailure;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.runtime.ModelClients;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
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

    // Covers: 6.5, 11, I8 (an HTTP failure's cause is its status, never the error body; only 429, 503 and 504 may be
    // tried again, and the client itself still sends one attempt)
    @Test
    void generate_httpErrors_causeIsTheStatus_retryableFor429And503Only() {
        Map<Integer, Boolean> cases = Map.of(400, false, 403, false, 404, false, 429, true, 500, false, 503, true, 504, true);
        int sent = 0;
        for (Map.Entry<Integer, Boolean> c : cases.entrySet()) {
            stub.answer(c.getKey(), "{\"error\":{\"code\":" + c.getKey() + ",\"message\":\"SECRET-BODY\"}}");
            GeminiModelClient client = client();
            assertThatThrownBy(() -> client.generate(ask("hi"), TOOLS)).as("HTTP %d", c.getKey())
                    .isInstanceOfSatisfying(GeminiException.class, e -> {
                        assertThat(e.cause()).isEqualTo("http_" + c.getKey());
                        assertThat(e.retryable()).as("retryable %d", c.getKey()).isEqualTo(c.getValue());
                        assertThat(e.retryAfter()).isEmpty();
                        assertThat(e.getMessage()).doesNotContain("SECRET");
                    });
            sent++;
            assertThat(stub.received()).as("one attempt for HTTP %d", c.getKey()).hasSize(sent);
        }
    }

    /** A 429 body as Gemini sends it: the message, the status and the details, here in the order given. */
    private static String rateLimited(String... details) {
        return "{\"error\": {\"code\": 429, \"message\": \"Quota exceeded.\", \"status\": \"RESOURCE_EXHAUSTED\","
                + " \"details\": [" + String.join(", ", details) + "]}}";
    }

    private static String retryInfo(String delay) {
        return "{\"@type\": \"type.googleapis.com/google.rpc.RetryInfo\", \"retryDelay\": \"" + delay + "\"}";
    }

    private static final String QUOTA_FAILURE = "{\"@type\": \"type.googleapis.com/google.rpc.QuotaFailure\","
            + " \"violations\": [{\"quotaMetric\": \"generate_content_free_tier_requests\", \"quotaValue\": \"10\"}]}";

    private Optional<Duration> retryAfterOf(int status, String body) {
        stub.answer(status, body);
        AtomicReference<Optional<Duration>> kept = new AtomicReference<>();
        assertThatThrownBy(() -> client().generate(ask("hi"), TOOLS)).as(body)
                .isInstanceOfSatisfying(GeminiException.class, e -> kept.set(e.retryAfter()));
        return kept.get();
    }

    // Covers: 6.3, 6.5 (a 429's or 503's wait is the retryDelay of the error's google.rpc.RetryInfo detail, which the SDK
    // passes on in its error's details, as seconds with up to nine decimals, wherever the detail stands among the others)
    @Test
    void generate_rateLimited_keepsTheRetryInfosDelay() {
        Map<String, Duration> delays = Map.of("7s", Duration.ofSeconds(7), "0s", Duration.ZERO, "1.5s",
                Duration.ofMillis(1500), "37.123456789s", Duration.ofSeconds(37, 123_456_789), "999999s",
                Duration.ofSeconds(999_999));
        for (Map.Entry<String, Duration> d : delays.entrySet()) {
            assertThat(retryAfterOf(429, rateLimited(QUOTA_FAILURE, retryInfo(d.getKey())))).as(d.getKey())
                    .contains(d.getValue());
        }
        assertThat(retryAfterOf(429, rateLimited(retryInfo("4s"), QUOTA_FAILURE))).as("the first detail")
                .contains(Duration.ofSeconds(4));
        assertThat(retryAfterOf(503, rateLimited(retryInfo("3s")).replace("429", "503"))).as("a 503")
                .contains(Duration.ofSeconds(3));
    }

    // Covers: 6.3, 6.5, I8 (Retry-After is not read, since the client keeps no header; a retryDelay in any other form,
    // a RetryInfo text inside another detail or in the message, and a body with no RetryInfo keep no wait: 2 s then)
    @Test
    void generate_rateLimited_withoutAUsableRetryInfo_keepsNoWait() {
        stub.retryAfter("7");
        assertThat(retryAfterOf(429, rateLimited(QUOTA_FAILURE))).as("Retry-After alone").isEmpty();
        stub.retryAfter(null);

        for (String other : List.of("7", "-3s", "s", "1.s", ".5s", "1234567s", "1.1234567891s", "7m", "1e3s", " 7s",
                "")) {
            assertThat(retryAfterOf(429, rateLimited(retryInfo(other)))).as("retryDelay \"%s\"", other).isEmpty();
        }
        // As the SDK writes a detail back out: compact, so these are the exact lines a RetryInfo would make.
        String compact = "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"9s\"}";
        String nested = "{\"@type\": \"type.googleapis.com/google.rpc.Help\", \"description\": \"\\n"
                + compact.replace("\"", "\\\"") + "\"}";
        assertThat(retryAfterOf(429, rateLimited(nested))).as("inside another detail's text").isEmpty();
        assertThat(retryAfterOf(429, "{\"error\": {\"code\": 429, \"message\": \"x\\n" + compact.replace("\"", "\\\"")
                + "\", \"status\": \"RESOURCE_EXHAUSTED\"}}")).as("in the message").isEmpty();
        assertThat(retryAfterOf(429, rateLimited())).as("no details").isEmpty();
        assertThat(retryAfterOf(429, "not json")).as("no body to read").isEmpty();
    }

    // Covers: 6.5, I8 (a key holding a character an HTTP header cannot carry, anywhere in it, is refused when the client
    // is built, with cause configuration and a message that never holds the key; OkHttp would refuse it only while building
    // the first request, as exception_IllegalArgumentException with nothing sent)
    @Test
    void construct_aKeyAHeaderCannotCarry_isRefusedWithCauseConfiguration() {
        for (String bad : List.of("\u200b", "\u00a0", "\n", " ", "\u00e9", "\t", "\u007f")) {
            String key = KEY.substring(0, 4) + bad + KEY.substring(4);
            assertThatThrownBy(() -> new GeminiModelClient(ModelKey.of(key), settings(), name -> null))
                    .as("U+%04X", (int) bad.charAt(0)).isInstanceOfSatisfying(GeminiException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.CONFIGURATION);
                        assertThat(e.cause()).isEqualTo("configuration");
                        assertThat(e.getMessage()).doesNotContain(KEY.substring(0, 4) + bad);
                    });
        }
        assertThat(stub.received()).isEmpty();
    }

    // Covers: 6.5, 6.2, I6 (the live request through the real SDK, with no network: the v3 prompt, the five real tool
    // schemas, the 20 s call timeout and LOW thinking are built, validated and sent by the SDK itself, so a setting the
    // SDK refuses fails in verify, not on the owner's first message)
    @Test
    void generate_theLiveRequest_isBuiltAndSentByTheRealSdk() {
        stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}}],"
                + USAGE + "}");
        List<ToolSpec> five = dev.ucplab.runtime.ToolRegistry.of(new dev.ucplab.runtime.ToolPorts(
                refusing(dev.ucplab.core.merchant.MerchantRegistry.class), Map.of(),
                refusing(dev.ucplab.core.intent.Orchestrator.class)), (q, t) -> Optional.empty(), Duration.ofSeconds(1))
                .specs();
        GeminiModelClient live = new GeminiModelClient(ModelKey.of(KEY), ModelClientSettings.defaults()
                .withBaseUrl(stub.url()).withCallTimeout(Duration.ofSeconds(20)), name -> null);

        ModelTurn turn = live.generate(new Conversation(dev.ucplab.runtime.SystemPrompt.text(),
                List.of(new Message.User("a snowboard under $700"))), five);

        assertThat(turn.text()).isEqualTo("ok");
        assertThat(stub.received()).singleElement().satisfies(r -> {
            assertThat(r.uri()).contains("gemini-3.8-flash:generateContent");
            assertThat(r.body()).contains("\"thinkingConfig\":{\"thinkingLevel\":\"LOW\"}");
            for (String name : dev.ucplab.runtime.ToolRegistry.NAMES) {
                assertThat(r.body()).contains("\"name\":\"" + name + "\"");
            }
        });
    }

    private static <T> T refusing(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> {
                    throw new UnsupportedOperationException("specs only");
                }));
    }

    // Covers: 6.5, 11 (a timeout and a dropped connection have their own causes, never retryable)
    @Test
    void generate_timeoutAndDroppedConnection_haveTheirCauses() throws Exception {
        stub.holding();
        GeminiModelClient slow = new GeminiModelClient(ModelKey.of(KEY), settings().withCallTimeout(Duration.ofMillis(300)),
                name -> null);
        assertThatThrownBy(() -> slow.generate(ask("hi"), TOOLS)).isInstanceOfSatisfying(GeminiException.class, e -> {
            assertThat(e.cause()).isEqualTo("timeout");
            assertThat(e.retryable()).isFalse();
        });

        try (GeminiStub.Dropping dropping = new GeminiStub.Dropping()) {
            GeminiModelClient dropped = new GeminiModelClient(ModelKey.of(KEY), settings().withBaseUrl(dropping.url()),
                    name -> null);
            assertThatThrownBy(() -> dropped.generate(ask("hi"), TOOLS)).isInstanceOfSatisfying(GeminiException.class,
                    e -> {
                        assertThat(e.cause()).matches("network_[A-Za-z0-9_]{1,56}");
                        assertThat(e.retryable()).isFalse();
                    });
        }
    }

    // Covers: 11, m5-stage5b (every GeminiException is a ModelFailure whose code is its reason's name)
    @Test
    void geminiException_isAModelFailure_codeIsTheReasonName() {
        for (Reason reason : Reason.values()) {
            assertThat(new GeminiException(reason)).isInstanceOf(ModelFailure.class)
                    .extracting(e -> ((ModelFailure) e).code()).isEqualTo(reason.name());
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
