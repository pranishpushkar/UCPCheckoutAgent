package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/** C1: the Boot context starts and serves {@code /healthz} (23.4). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ServiceApplicationTest {

    @LocalServerPort
    int port;

    // Covers: 23.4 (GET /healthz is 200 while the process is up)
    @Test
    void healthz_running_is200() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"up\"");
    }

    // Covers: section 11 (System.Logger and JUL records from core and runtime reach Logback; m6-plan VERIFY)
    @Test
    void logging_systemLoggerAndJul_reachLogback(CapturedOutput output) {
        System.getLogger("dev.ucplab.core.c1probe").log(System.Logger.Level.WARNING, "c1-system-logger-probe");
        Logger.getLogger("dev.ucplab.runtime.c1probe").warning("c1-jul-probe");

        // Logback's console pattern writes " WARN " and the (abbreviated) logger name; JUL's own ConsoleHandler would
        // write "WARNING: <message>" instead, so that form must be absent.
        assertThat(output.getAll()).containsPattern(" WARN .*c1probe.*c1-system-logger-probe")
                .containsPattern(" WARN .*c1probe.*c1-jul-probe")
                .doesNotContain("WARNING: c1-system-logger-probe")
                .doesNotContain("WARNING: c1-jul-probe");
    }
}
