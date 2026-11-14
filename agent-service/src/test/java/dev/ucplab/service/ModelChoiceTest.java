package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.run.DemoModel;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code AGENT_MODEL} (25.4, m6-plan C14): {@code scripted} is the default; {@code gemini} refuses to start without a
 * key; with a fake key it talks to a loopback stub, and a resumed run sends the stored function-call parts back
 * (risk 11). Nothing here uses a real key or the network.
 */
class ModelChoiceTest {

    private static final String FAKE_KEY = "fake-key-not-a-secret-9876543210";
    private static final String CALL = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":"
            + "{\"name\":\"get_product\",\"args\":{\"item_id\":\"item_123\"}}}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":5}}";
    private static final String TEXT = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
            + "\"Here it is.\"}]},\"finishReason\":\"STOP\"}],"
            + "\"usageMetadata\":{\"promptTokenCount\":60,\"candidatesTokenCount\":4}}";

    @TempDir
    Path dir;

    private TestService service;
    private HttpServer stub;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        if (stub != null) {
            stub.stop(0);
        }
    }

    private static Map<String, String> env(Path dir, String... extra) {
        Map<String, String> env = new HashMap<>(TestService.baseEnv(dir));
        for (int i = 0; i < extra.length; i += 2) {
            env.put(extra[i], extra[i + 1]);
        }
        return env;
    }

    // Covers: 25.4 (scripted is the default: no key, and the service's own demo model is the one in use)
    @Test
    void scripted_isTheDefault() throws IOException {
        service = TestService.start(dir);

        assertThat(service.bean(ModelClient.class)).isInstanceOf(DemoModel.class);
        assertThat(service.bean(dev.ucplab.service.config.ServiceProperties.class).agentModel())
                .isEqualTo(dev.ucplab.service.config.ServiceProperties.AgentModel.SCRIPTED);
    }

    // Covers: 25.4, I8 (gemini without a key refuses to start, with a fixed message that holds no value; so does an
    // unknown model name and a stub URL that is not loopback)
    @Test
    void gemini_withoutAKey_refusesToStart() {
        assertThatThrownBy(() -> TestService.start(dir, Map.of("AGENT_MODEL", "gemini"), Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_MODEL=gemini needs GEMINI_API_KEY");
        assertThatThrownBy(() -> TestService.start(dir, Map.of("AGENT_MODEL", "gemini", "GEMINI_API_KEY", "  "),
                Optional.empty())).isInstanceOf(IllegalStateException.class)
                .hasMessage("AGENT_MODEL=gemini needs GEMINI_API_KEY");
        assertThatThrownBy(() -> TestService.start(dir, Map.of("AGENT_MODEL", "gpt"), Optional.empty()))
                .isInstanceOf(IllegalStateException.class).hasMessage("AGENT_MODEL must be scripted or gemini");
        assertThatThrownBy(() -> TestService.start(dir, Map.of("AGENT_MODEL", "gemini", "GEMINI_API_KEY", FAKE_KEY,
                "GEMINI_BASE_URL", "http://evil.example/v1"), Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GEMINI_BASE_URL must be an http URL on a loopback address")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(FAKE_KEY).doesNotContain("evil"));
    }

    // Covers: I8 (the key is read by Environment only: only Environment, which reads the process environment, and ServiceProperties name it, and it holds the key
    // only as a ModelKey, whose toString and the properties' own are redacted)
    @Test
    void theKey_isNamedByServicePropertiesOnly_andNeverPrinted() throws IOException {
        List<String> naming = new CopyOnWriteArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readString(p).contains("GEMINI_API_KEY")) {
                    naming.add(p.getFileName().toString());
                }
            }
        }
        // Environment names it in its comment only; ServiceProperties asks Environment for it.
        assertThat(naming).containsExactlyInAnyOrder("Environment.java", "ServiceProperties.java");

        dev.ucplab.service.config.ServiceProperties properties = dev.ucplab.service.config.ServiceProperties.from(
                dev.ucplab.service.config.Environment.of(env(dir, "AGENT_MODEL", "gemini", "GEMINI_API_KEY", FAKE_KEY)));
        assertThat(properties.toString()).doesNotContain(FAKE_KEY);
        assertThat(properties.modelKey().orElseThrow().toString()).doesNotContain(FAKE_KEY);
        assertThat(dev.ucplab.service.config.Environment.of(env(dir, "GEMINI_API_KEY", FAKE_KEY)).toString())
                .doesNotContain(FAKE_KEY);
    }

    // Covers: 6.5, 23.1, risk 11, I8 (gemini with a fake key runs one turn against a loopback stub: a function call, the
    // tool, then text; a continuation then sends the stored function-call and function-response parts back, and the key
    // never appears in any API response)
    @Test
    void gemini_againstAStub_runsATurn_andAResumedRunSendsTheStoredCallParts() throws IOException {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger n = new AtomicInteger();
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = (n.incrementAndGet() == 1 ? CALL : TEXT).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
        service = TestService.start(dir, Map.of("AGENT_MODEL", "gemini", "GEMINI_API_KEY", FAKE_KEY,
                "GEMINI_BASE_URL", "http://127.0.0.1:" + stub.getAddress().getPort()), Optional.empty());
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");

        HttpResponse<String> first = api.post(alice, "/api/runs", "{\"message\":\"show me item_123\"}");

        assertThat(first.statusCode()).isEqualTo(201);
        Map<String, Object> run = LenientJson.readObject(first.body());
        assertThat(run).containsEntry("status", "done");
        assertThat(requests).hasSize(2);
        String id = (String) run.get("run_id");
        Map<String, Object> full = LenientJson.readObject(api.get(alice, "/api/runs/" + id).body());
        assertThat(full.get("model").toString()).startsWith("gemini/");
        assertThat(full.toString()).contains("get_product");

        HttpResponse<String> resumed = api.post(alice, "/api/runs/" + id + "/messages", "{\"message\":\"thanks\"}");

        assertThat(resumed.statusCode()).isEqualTo(200);
        assertThat(requests).hasSize(3);
        String third = requests.get(2);
        assertThat(third).contains("\"functionCall\"").contains("\"functionResponse\"").contains("get_product")
                .contains("show me item_123").contains("thanks");
        for (HttpResponse<String> r : api.seen) {
            assertThat(r.body()).doesNotContain(FAKE_KEY);
        }
    }
}
