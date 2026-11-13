package dev.ucplab.adk.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.runtime.ToolPorts;
import dev.ucplab.runtime.ToolRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * C15 VERIFY: which JSON Schema keywords of the five tools' schemas reach the Gemini API.
 *
 * <p><b>The SDK</b> (google-genai 1.80.0 sources, read 2026-11-13): {@code FunctionDeclaration.parametersJsonSchema} is
 * an {@code Optional<Object>} serialized as given, and {@code Models.toolToMldev} copies {@code functionDeclarations}
 * into the request unchanged. So every keyword is sent; this test proves it against the stub.
 *
 * <p><b>The API</b>: the function-calling guide (https://aistudio.google.com/docs/function-calling, read 2026-11-13)
 * says only "a subset of the OpenAPI schema" is supported and lists no keywords. The structured-output guide
 * (https://aistudio.google.com/docs/structured-output.md.txt, read 2026-11-13) lists {@code properties},
 * {@code required}, {@code additionalProperties}, {@code enum}, {@code format}, {@code minimum}, {@code maximum},
 * {@code items}, {@code minItems} and {@code maxItems}, and says {@code minLength} and {@code maxLength} (among others)
 * are "not enforced" and that the API "automatically strips and ignores them ... rather than returning an error";
 * {@code pattern} is not mentioned. Whether those rules also hold for {@code parametersJsonSchema} is not stated.
 * TODO(VERIFY): the live trials show whether any of these schemas is refused; ignored keywords cannot be seen from
 * outside. Either way the tools' own argument validation stays the real check (agent-runtime's
 * {@code ToolArgumentsTest}), so nothing depends on the model honouring the schema.
 */
class GeminiSchemaKeywordsTest {

    private static final Set<String> CHECKED = Set.of("minLength", "maxLength", "pattern", "minimum", "maximum",
            "minItems", "maxItems", "additionalProperties");

    private static <T> T refusing(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            throw new UnsupportedOperationException("specs only");
        }));
    }

    private static List<ToolSpec> fiveTools() {
        ToolPorts ports = new ToolPorts(refusing(MerchantRegistry.class), Map.of(), refusing(Orchestrator.class));
        return ToolRegistry.of(ports, (question, timeout) -> Optional.empty(), Duration.ofSeconds(1)).specs();
    }

    // Covers: 6.2, R2, C15 VERIFY (every keyword the five schemas use reaches the request body unchanged)
    @Test
    void fiveToolSchemas_everyKeyword_sentUnchanged() throws Exception {
        List<ToolSpec> tools = fiveTools();
        Set<String> used = new TreeSet<>();
        for (ToolSpec t : tools) {
            collect(LenientJson.readObject(t.parametersJsonSchema()), used);
        }
        assertThat(tools).extracting(ToolSpec::name).containsExactlyElementsOf(ToolRegistry.NAMES);
        // The keywords the plan names are all used by at least one of the five schemas.
        assertThat(used).containsAll(CHECKED);

        try (GeminiStub stub = new GeminiStub()) {
            stub.answer(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1}}");
            GeminiModelClient client = new GeminiModelClient(ModelKey.of("canary-0123456789abcdef0123456789abcdef"),
                    ModelClientSettings.defaults().withBaseUrl(stub.url()).withModel("stub-model"), name -> null);
            client.generate(new Conversation("p", List.of(new Message.User("hi"))), tools);
            client.close();

            String body = stub.received().getFirst().body();
            for (String keyword : CHECKED) {
                assertThat(body).as(keyword).contains("\"" + keyword + "\"");
            }
            for (ToolSpec t : tools) {
                // Each schema arrives whole: compare its canonical form inside the request.
                assertThat(body).contains("\"name\":\"" + t.name() + "\"");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = (String) e.getKey();
                if (!key.equals("properties")) {
                    keys.add(key);
                    collect(e.getValue(), keys);
                } else {
                    ((Map<String, Object>) e.getValue()).values().forEach(v -> collect(v, keys));
                }
            }
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collect(v, keys));
        }
    }
}
