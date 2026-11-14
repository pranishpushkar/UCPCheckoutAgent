package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.LeakEvidence.Allowed;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Logging and the I8 scan's own controls (m6-plan section 10, C16): the scan finds what is planted, the redaction
 * customiser masks credential shapes logged through the real logging setup, a request log line carries no query, header
 * or body, and the container's access log and Spring's request details are off. Covers: I8, 11, NFR6.
 */
class LoggingTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    private LogCapture logs;
    private TestService service;
    private HttpServer http;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        if (http != null) {
            http.stop(0);
        }
        if (logs != null) {
            logs.close();
        }
    }

    private static String canary(String kind) {
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        return kind + "-canary-" + HexFormat.of().formatHex(b);
    }

    // Covers: I8 (positive controls: a canary planted in a log line, a response body, a response header that does not
    // deliver it, and a database column is found, one by one, for each of the six canaries)
    @Test
    void scan_findsEachPlantedCanary_inALogLine_aBody_aHeader_andTheDatabase() throws Exception {
        Map<String, Allowed> kinds = new java.util.LinkedHashMap<>();
        kinds.put("approval-secret", Allowed.NOWHERE);
        kinds.put("payment-token", Allowed.NOWHERE);
        kinds.put("model-key", Allowed.NOWHERE);
        kinds.put("session-cookie", Allowed.SET_COOKIE_HEADER);
        kinds.put("csrf-token", Allowed.CSRF_HEADER);
        kinds.put("approval-token", Allowed.APPROVAL_BODY);
        for (Map.Entry<String, Allowed> kind : kinds.entrySet()) {
            String value = canary(kind.getKey());
            Path db = dir.resolve(kind.getKey() + ".db");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE notes (id INTEGER, text TEXT)");
                st.execute("INSERT INTO notes VALUES (1, 'x " + value + " y')");
            }
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/", ex -> {
                byte[] out = ("{\"echo\":\"" + value + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("X-Elsewhere", value);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            http.start();
            ApiClient api = new ApiClient(http.getAddress().getPort());
            api.get("/not-the-delivering-place");
            http.stop(0);

            for (String where : List.of("log", "body", "header", "database")) {
                LeakEvidence evidence = new LeakEvidence();
                evidence.canary(kind.getKey(), value, kind.getValue());
                switch (where) {
                    case "log" -> evidence.logs(List.of("{\"message\":\"nothing\"}", "text " + value + " more"));
                    case "body", "header" -> evidence.clients(api);
                    default -> evidence.database(db);
                }
                assertThat(evidence.findings()).as("%s planted in the %s", kind.getKey(), where)
                        .isNotEmpty().allMatch(f -> f.startsWith(kind.getKey()));
            }
        }
    }

    // Covers: I8, 18.5, 23.2 (the scan allows exactly the delivering places: the approval token in a 200 approval GET
    // body, the cookie in Set-Cookie, the CSRF token in X-CSRF-Token; and finds the same values anywhere else)
    @Test
    void scan_allowsTheDeliveringPlacesOnly() throws Exception {
        String token = canary("approval-token");
        String cookie = canary("session-cookie");
        String csrf = canary("csrf-token");
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/api/intents/abc/approval", ex -> {
            byte[] out = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        http.createContext("/auth/dev-login", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "ucplab_session=" + cookie + "; HttpOnly");
            ex.getResponseHeaders().add("X-CSRF-Token", csrf);
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        http.createContext("/api/intents/abc/events", ex -> {
            byte[] out = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        http.start();
        ApiClient api = new ApiClient(http.getAddress().getPort());
        api.get("/api/intents/abc/approval");
        api.get("/auth/dev-login");
        LeakEvidence evidence = new LeakEvidence();
        evidence.canary("approval token", token, Allowed.APPROVAL_BODY);
        evidence.canary("cookie", cookie, Allowed.SET_COOKIE_HEADER);
        evidence.canary("csrf", csrf, Allowed.CSRF_HEADER);
        evidence.clients(api);

        assertThat(evidence.findings()).isEmpty();

        api.get("/api/intents/abc/events");
        assertThat(evidence.findings()).containsExactly("approval token in the body of GET /api/intents/abc/events");
    }

    // Covers: I8, 11 (through the real logging setup, each credential shape comes out masked: the canary is gone and the
    // mask is there, in the message and in an exception's text)
    @Test
    void credentialShapes_loggedThroughTheRealSetup_comeOutMasked() throws IOException {
        logs = new LogCapture();
        service = TestService.start(dir);
        Logger plant = LoggerFactory.getLogger("test.plant");
        String c = canary("shape");

        plant.warn("auth was Bearer {}", c);
        plant.warn("sent Cookie: ucplab_session={}", c);
        plant.warn("sent X-CSRF-Token: {}", c);
        plant.warn("body was {\"token\":\"{}\"}", c);
        plant.warn("body was {\"credential\":{\"type\":\"MOCK\",\"token\":\"{}\"}}", c);
        plant.warn("sent Authorization: Basic {}", c);
        plant.warn("sent x-goog-api-key: {}", c);
        plant.warn("sent Set-Cookie: ucplab_session={}; Path=/", c);
        plant.error("failed", new IllegalStateException("upstream said Bearer " + c));
        plant.warn("a card tok_{}", c);

        String out = logs.text();
        assertThat(out).doesNotContain(c);
        assertThat(out.lines().filter(l -> l.contains("test.plant")).toList()).hasSize(10)
                .allMatch(l -> l.contains("[redacted]"));
    }

    // Covers: I8, 11 (a request log line holds the method, the path template, the status, the duration and the user:
    // never the query string, a header, the cookie or the body)
    @Test
    void requestLogLine_neverCarriesTheQuery_aHeader_orABody() throws IOException {
        logs = new LogCapture();
        service = TestService.start(dir);
        ApiClient api = new ApiClient(service.port());
        Session alice = api.signIn("alice");
        String query = canary("query");
        String header = canary("header");
        String body = canary("body");

        api.send("GET", "/api/runs/some-run-id?probe=" + query, Optional.empty(), "Cookie",
                "ucplab_session=" + alice.cookie(), "X-Probe", header);
        api.post(alice, "/api/runs", "{\"message\":\"" + body + "\"}");
        api.get("/no/such/path?x=" + query);

        List<String> lines = logs.lines().stream().filter(l -> l.contains("\"logger\":\"dev.ucplab.request\"")).toList();
        assertThat(lines.size()).isGreaterThanOrEqualTo(4);
        Pattern shape = Pattern.compile("request method=[A-Z]+ path=\\S+ status=\\d{3} took_ms=\\d+( user=\\S+)?");
        for (String line : lines) {
            String message = (String) LenientJson.readObject(line).get("message");
            assertThat(message).matches(shape);
            assertThat(line).doesNotContain(query).doesNotContain(header).doesNotContain(body)
                    .doesNotContain(alice.cookie()).doesNotContain(alice.csrf());
        }
        assertThat(lines).anyMatch(l -> l.contains("path=/api/runs/{") && l.contains("user=local-alice"));
        assertThat(lines).anyMatch(l -> l.contains("path=unmatched"));
    }

    // Covers: 11 (the JSON request line also carries the request id from the MDC, the same one the response header names)
    @Test
    void requestLine_carriesTheRequestId() throws IOException {
        logs = new LogCapture();
        service = TestService.start(dir);
        ApiClient api = new ApiClient(service.port());

        String id = api.get("/healthz").headers().firstValue("X-Request-Id").orElseThrow();

        assertThat(logs.lines()).anyMatch(l -> l.contains("\"logger\":\"dev.ucplab.request\"")
                && l.contains("\"request_id\":\"" + id + "\""));
    }

    // Covers: I8, 11 (Tomcat's access log and Spring MVC's request-details logging are both off, and the output is
    // structured JSON through the redaction customiser)
    @Test
    void accessLogAndRequestDetails_areOff_andTheOutputIsStructured() throws IOException {
        service = TestService.start(dir);
        Environment env = service.context.getEnvironment();

        assertThat(env.getProperty("server.tomcat.accesslog.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.mvc.log-request-details", Boolean.class)).isFalse();
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(env.getProperty("logging.structured.json.customizer"))
                .isEqualTo("dev.ucplab.service.logging.RedactingLogCustomizer");
    }
}
