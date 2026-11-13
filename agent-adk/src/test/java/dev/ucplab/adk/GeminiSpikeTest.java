package dev.ucplab.adk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
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
 * The Gemini spike (C1, ADR 0004, R2), offline: one {@code client.models.generateContent} call against a loopback stub
 * of the Gemini API, with the base URL overridden. It offers one function declaration and gets the function call back
 * unexecuted, with token counts. The key travels only in the {@code x-goog-api-key} header, never in the URL, and a
 * failed call is sent exactly once when retries are set to one attempt.
 */
class GeminiSpikeTest {

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

    /** An explicit client: key, base URL, backend and retries all set, none taken from the environment. */
    private Client client() {
        return Client.builder()
                .apiKey(key)
                .vertexAI(false)
                .httpOptions(HttpOptions.builder()
                        .baseUrl("http://127.0.0.1:" + stub.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
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

    private static List<Content> ask(String text) {
        return List.of(Content.fromParts(Part.fromText(text)));
    }

    // Covers: 6.5, R2 (one call: the function call comes back unexecuted, with token counts, and the request carries
    // the declaration)
    @Test
    void oneCall_functionDeclaration_returnsTheCallUnexecutedWithUsage() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_time",\
                "args":{"zone":"UTC"}}}]},"finishReason":"STOP"}],\
                "usageMetadata":{"promptTokenCount":42,"candidatesTokenCount":7,"totalTokenCount":49}}""";

        GenerateContentResponse response = client().models.generateContent(MODEL, ask("What time is it in UTC?"),
                withGetTime());

        List<FunctionCall> calls = response.candidates().orElseThrow().getFirst().content().orElseThrow()
                .parts().orElseThrow().stream().flatMap(p -> p.functionCall().stream()).toList();
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
            assertThat(r.body()).contains("\"functionDeclarations\"").contains("\"get_time\"")
                    .contains("\"parametersJsonSchema\"");
        });
    }

    // Covers: R2 (the base URL override sends the call to the stub, on the generateContent path, and the function
    // response goes back by name)
    @Test
    void baseUrlOverride_reachesTheStub_functionResponseByName() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"It is 12:00 in UTC."}]},\
                "finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":60,"candidatesTokenCount":9}}""";
        List<Content> contents = List.of(
                Content.fromParts(Part.fromText("What time is it in UTC?")),
                Content.builder().role("model")
                        .parts(List.of(Part.fromFunctionCall("get_time", Map.of("zone", "UTC")))).build(),
                Content.fromParts(Part.fromFunctionResponse("get_time", Map.of("time", "12:00"))));

        GenerateContentResponse response = client().models.generateContent(MODEL, contents, withGetTime());

        assertThat(response.text()).isEqualTo("It is 12:00 in UTC.");
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.uri()).isEqualTo(GENERATE);
            assertThat(r.body()).contains("\"functionResponse\"").contains("\"name\":\"get_time\"")
                    .contains("\"12:00\"");
        });
    }

    // Covers: I8, R2 (the key is sent in the x-goog-api-key header only: never in the URL or the body)
    @Test
    void apiKey_travelsInTheHeaderOnly_neverInTheUrlOrBody() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""";

        client().models.generateContent(MODEL, ask("hi"), null);

        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.keyHeader()).isEqualTo(key);
            assertThat(r.uri()).doesNotContain(key).doesNotContain("key=");
            assertThat(r.body()).doesNotContain(key);
        });
    }

    // Covers: 6.3, I8, R2 (with one attempt a 503 is sent once, not retried behind the budget's back, and no message
    // in the exception chain holds the key)
    @Test
    void serverError_oneAttempt_sentOnceAndTheErrorHoldsNoKey() {
        status = 503;
        reply = """
                {"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""";
        Client client = client();

        assertThatThrownBy(() -> client.models.generateContent(MODEL, ask("hi"), null))
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(key);
                    }
                });
        assertThat(received).hasSize(1);
    }
}
