package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.ToolSpec;
import dev.ucplab.runtime.ModelClients;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C1 (m6-plan sections 2.1 and 2.3): agent-adk is on agent-service's classpath at runtime scope only, and the live
 * client works on the versions this module resolves (Spring Boot's BOM, Jackson 2 pinned, kotlin-stdlib from the BOM).
 * Offline: a loopback stub of the Gemini API and a fake key.
 */
class LiveProviderClasspathTest {

    private static final String FAKE_KEY = "fake-key-not-a-secret-0123456789";
    private static final String REPLY = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
            + "{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_123\"}}}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":5}}";

    private HttpServer stub;

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] out = REPLY.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    // Covers: 5.2, 6.5 (the provider is found through ServiceLoader, with no Gemini type on the compile classpath)
    @Test
    void modelClients_gemini_isAvailable() {
        assertThat(ModelClients.available("gemini")).isTrue();
    }

    // Covers: 6.5, R2 (one generate maps a function call on agent-service's resolved classpath)
    @Test
    void generate_againstStub_mapsTheFunctionCall() {
        ModelClientSettings settings = ModelClientSettings.defaults().withModel("stub-model")
                .withBaseUrl(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()))
                .withCallTimeout(Duration.ofSeconds(5));
        ToolSpec tool = new ToolSpec("get_product", "Look up a product.", "{\"type\":\"object\",\"properties\":"
                + "{\"item_id\":{\"type\":\"string\"}},\"required\":[\"item_id\"]}");

        ModelTurn turn = ModelClients.load("gemini", ModelKey.of(FAKE_KEY), settings)
                .generate(new Conversation("You are a shopping agent.", List.of(new Message.User("a mug"))),
                        List.of(tool));

        assertThat(turn.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("get_product");
            assertThat(c.arguments()).isEqualTo(Map.of("item_id", "item_123"));
        });
    }
}
