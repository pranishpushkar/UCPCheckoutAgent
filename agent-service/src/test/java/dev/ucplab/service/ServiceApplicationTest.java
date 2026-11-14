package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.service.config.Environment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** C1 and C9: the service starts and serves {@code /healthz} (23.4), and refuses the profile mixes of 18 and D10. */
@ExtendWith(OutputCaptureExtension.class)
class ServiceApplicationTest {

    @TempDir
    Path dir;

    // Covers: 23.4 (GET /healthz is 200 while the process is up)
    @Test
    void healthz_running_is200() throws IOException, InterruptedException {
        try (TestService service = TestService.start(dir)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"up\"");
        }
    }

    // Covers: section 11 (System.Logger and JUL records from core and runtime reach Logback; m6-plan VERIFY)
    @Test
    void logging_systemLoggerAndJul_reachLogback(CapturedOutput output) throws IOException {
        try (TestService service = TestService.start(dir)) {
            System.getLogger("dev.ucplab.core.c1probe").log(System.Logger.Level.WARNING, "c1-system-logger-probe");
            Logger.getLogger("dev.ucplab.runtime.c1probe").warning("c1-jul-probe");
        }

        // Logback writes a structured line (C16): the level, the logger and the message as JSON members. JUL's own
        // ConsoleHandler would write "WARNING: <message>" instead, so that form must be absent.
        assertThat(output.getAll())
                .containsPattern("\"level\":\"WARN\",\"logger\":\"dev.ucplab.core.c1probe\".*\"message\":\"c1-system-logger-probe\"")
                .containsPattern("\"level\":\"WARN\",\"logger\":\"dev.ucplab.runtime.c1probe\".*\"message\":\"c1-jul-probe\"")
                .doesNotContain("WARNING: c1-system-logger-probe")
                .doesNotContain("WARNING: c1-jul-probe");
    }

    // Covers: 18 (local together with prod refuses to start, before any database is created)
    @Test
    void start_localWithProdActive_isRefused() {
        Map<String, String> env = TestService.baseEnv(dir);

        assertThatThrownBy(() -> ServiceApplication.start(Environment.of(env), List.of(), "--server.port=0",
                "--spring.profiles.active=prod")).hasMessage("the local and prod profiles cannot both be active");
        assertThat(Files.exists(dir.resolve("service.db"))).isFalse();
    }

    // Covers: D10 (AGENT_PROFILE=demo refuses to start in M6)
    @Test
    void start_demo_isRefused() {
        Map<String, String> env = new java.util.HashMap<>(TestService.baseEnv(dir));
        env.put("AGENT_PROFILE", "demo");

        assertThatThrownBy(() -> ServiceApplication.start(Environment.of(env), List.of(), "--server.port=0"))
                .hasMessage("AGENT_PROFILE=demo is not available in M6");
    }
}
