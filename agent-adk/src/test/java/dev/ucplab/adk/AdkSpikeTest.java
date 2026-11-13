package dev.ucplab.adk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.adk.models.Gemini;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ADK spike (C1, ADR 0004, R2), offline: ADK's {@code Gemini} model makes one model turn against a loopback stub
 * of the Gemini API, outside ADK's runner. It offers one function declaration, gets a function call back unexecuted,
 * and sends a function response back by name. The key travels only in the {@code x-goog-api-key} header, never in the
 * URL, and a failed call is sent exactly once when retries are set to one attempt.
 */
class AdkSpikeTest {

    private static final String MODEL = "spike-model";
    private static final String GENERATE = "/v1beta/models/" + MODEL + ":generateContent";

    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final String key = canary();
    private volatile int status = 200;
    private volatile String reply = "";
    private HttpServer stub;

    private record Received(String method, String uri, String keyHeader, String body) {
    }

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    ex.getRequestHeaders().getFirst("x-goog-api-key"), body));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private static String canary() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return "canary-" + HexFormat.of().formatHex(b);
    }

    /** ADK's Gemini model over an explicit genai client: key, base URL and backend all set, none read from the environment. */
    private Gemini model() {
        Client client = Client.builder()
                .apiKey(key)
                .vertexAI(false)
                .httpOptions(HttpOptions.builder()
                        .baseUrl("http://127.0.0.1:" + stub.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        return new Gemini(MODEL, client);
    }

    private static GenerateContentConfig withGetTime() {
        FunctionDeclaration getTime = FunctionDeclaration.builder()
                .name("get_time")
                .description("The current time in a time zone.")
                .parametersJsonSchema(Map.of("type", "object",
                        "properties", Map.of("zone", Map.of("type", "string")),
                        "required", List.of("zone")))
                .build();
        return GenerateContentConfig.builder()
                .tools(List.of(Tool.builder().functionDeclarations(List.of(getTime)).build()))
                .build();
    }

    // Covers: 6.5, R2 (one model turn outside ADK's runner: the function call comes back unexecuted, with token counts)
    @Test
    void oneTurn_functionDeclaration_returnsTheCallUnexecutedWithUsage() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_time",\
                "args":{"zone":"UTC"}}}]},"finishReason":"STOP"}],\
                "usageMetadata":{"promptTokenCount":42,"candidatesTokenCount":7,"totalTokenCount":49}}""";
        LlmRequest request = LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("What time is it in UTC?"))))
                .config(withGetTime())
                .build();

        LlmResponse response = model().generateContent(request, false).blockingFirst();

        List<FunctionCall> calls = response.content().orElseThrow().parts().orElseThrow().stream()
                .flatMap(p -> p.functionCall().stream()).toList();
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.name()).hasValue("get_time");
            assertThat(c.args()).hasValue(Map.of("zone", "UTC"));
        });
        assertThat(response.usageMetadata()).hasValueSatisfying(u -> {
            assertThat(u.promptTokenCount()).hasValue(42);
            assertThat(u.candidatesTokenCount()).hasValue(7);
        });
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.uri()).isEqualTo(GENERATE);
            assertThat(r.body()).contains("\"functionDeclarations\"").contains("\"get_time\"")
                    .contains("\"parametersJsonSchema\"");
        });
    }

    // Covers: 6.5, R2 (the function response goes back as a functionResponse part matched by name)
    @Test
    void secondTurn_functionResponseByName_modelAnswersInText() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"It is 12:00 in UTC."}]},\
                "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":60,"candidatesTokenCount":9}}""";
        LlmRequest request = LlmRequest.builder()
                .contents(List.of(
                        Content.fromParts(Part.fromText("What time is it in UTC?")),
                        Content.builder().role("model")
                                .parts(List.of(Part.fromFunctionCall("get_time", Map.of("zone", "UTC")))).build(),
                        Content.fromParts(Part.fromFunctionResponse("get_time", Map.of("time", "12:00")))))
                .config(withGetTime())
                .build();

        LlmResponse response = model().generateContent(request, false).blockingFirst();

        assertThat(response.content().orElseThrow().text()).isEqualTo("It is 12:00 in UTC.");
        assertThat(received).singleElement().satisfies(r -> assertThat(r.body())
                .contains("\"functionResponse\"").contains("\"name\":\"get_time\"").contains("\"12:00\""));
    }

    // Covers: I8, R2 (the key is sent in the x-goog-api-key header only: never in the URL or the body)
    @Test
    void apiKey_travelsInTheHeaderOnly_neverInTheUrlOrBody() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""";
        model().generateContent(LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("hi")))).build(), false).blockingFirst();

        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.keyHeader()).isEqualTo(key);
            assertThat(r.uri()).doesNotContain(key).doesNotContain("key=");
            assertThat(r.body()).doesNotContain(key);
        });
    }

    // Covers: 6.3, I8, R2 (with one attempt a 503 is sent once, not retried behind the budget's back, and the error
    // does not carry the key)
    @Test
    void serverError_oneAttempt_sentOnceAndTheErrorHoldsNoKey() {
        status = 503;
        reply = """
                {"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""";

        assertThatThrownBy(() -> model().generateContent(LlmRequest.builder()
                .contents(List.of(Content.fromParts(Part.fromText("hi")))).build(), false).blockingFirst())
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(key);
                    }
                });
        assertThat(received).hasSize(1);
    }
}
