package dev.ucplab.adk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ADK spike's one live round trip (C1, ADR 0004, R2): runs only under {@code -Plive-spike} with
 * {@code GEMINI_API_KEY} set, never in a plain verify. The model is offered {@code get_time}, calls it, gets the result
 * from this test's own loop (ADK's runner is not used) and answers. It prints the turn count, the tool call and the
 * token counts; never the key, a request URL or a header.
 *
 * <p>The model id is {@code gemini-3.8-flash}, the stable Flash model listed at https://aistudio.google.com/docs/models
 * (where https://ai.google.dev/gemini-api/docs/models redirects) on 2026-11-13; {@code -Dspike.model} overrides it. The
 * pinned model for the agent is chosen in C15.
 */
class AdkLiveSpikeIT {

    private static final int MAX_TURNS = 3;

    // Covers: 6.5, R2 (one live tool-call round trip with our own loop owning the tool call)
    @Test
    void liveRoundTrip_modelCallsGetTimeThenAnswers() {
        String key = System.getenv("GEMINI_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "GEMINI_API_KEY is not set");
        String modelId = System.getProperty("spike.model", "gemini-3.8-flash");
        Client client = Client.builder()
                .apiKey(key)
                .vertexAI(false)
                .httpOptions(HttpOptions.builder()
                        .baseUrl("https://generativelanguage.googleapis.com")
                        .timeout(30_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
        Gemini model = new Gemini(modelId, client);
        FunctionDeclaration getTime = FunctionDeclaration.builder()
                .name("get_time")
                .description("Returns the current time in the given IANA time zone, for example UTC.")
                .parametersJsonSchema(Map.of("type", "object",
                        "properties", Map.of("zone", Map.of("type", "string")),
                        "required", List.of("zone")))
                .build();
        GenerateContentConfig config = GenerateContentConfig.builder()
                .tools(List.of(Tool.builder().functionDeclarations(List.of(getTime)).build()))
                .maxOutputTokens(256)
                .build();
        List<Content> contents = new ArrayList<>();
        contents.add(Content.fromParts(Part.fromText("Use the get_time tool to tell me the time in UTC.")));

        List<String> toolCalls = new ArrayList<>();
        String answer = "";
        long input = 0;
        long output = 0;
        int turns = 0;
        while (turns < MAX_TURNS) {
            LlmResponse response = model.generateContent(
                    LlmRequest.builder().contents(List.copyOf(contents)).config(config).build(), false).blockingFirst();
            turns++;
            input += response.usageMetadata().flatMap(u -> u.promptTokenCount()).orElse(0);
            output += response.usageMetadata().flatMap(u -> u.candidatesTokenCount()).orElse(0);
            Content content = response.content().orElseThrow();
            List<FunctionCall> calls = content.parts().orElse(List.of()).stream()
                    .flatMap(p -> p.functionCall().stream()).toList();
            if (calls.isEmpty()) {
                answer = content.text() == null ? "" : content.text();
                break;
            }
            contents.add(content);
            for (FunctionCall call : calls) {
                toolCalls.add(call.name().orElse("?") + " " + call.args().orElse(Map.of())
                        + " id " + call.id().orElse("(none)"));
                contents.add(Content.fromParts(
                        Part.fromFunctionResponse(call.name().orElseThrow(), Map.of("time", "12:00 UTC"))));
            }
        }

        System.out.println("AdkLiveSpikeIT: model " + modelId + ", turns " + turns + ", tool calls " + toolCalls
                + ", input tokens " + input + ", output tokens " + output + ", answer length " + answer.length()
                + ", answer mentions 12:00 " + answer.contains("12:00"));
        assertThat(toolCalls).as("the model called get_time").anySatisfy(c -> assertThat(c).startsWith("get_time"));
        assertThat(answer).as("a text answer after the tool result").isNotBlank();
    }
}
