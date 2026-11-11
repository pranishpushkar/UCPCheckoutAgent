package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.merchant.FrozenBody;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpTransportTest {

    private record Seen(String method, String path, Map<String, List<String>> headers, byte[] body) {}

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private JdkHttpTransport transport;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/echo", ex -> {
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders(),
                    ex.getRequestBody().readAllBytes()));
            respond(ex, 201, "{\"ok\":true}");
        });
        server.createContext("/redirect", ex -> {
            requests.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/echo");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.createContext("/big", ex -> respond(ex, 200, "x".repeat(4096)));
        server.start();
        transport = new JdkHttpTransport(UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\""));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Covers: 8.4, I7 (method, headers and body bytes reach the server exactly as given; the status and the
    // response headers come back as a reply for any status)
    @Test
    void send_deliversRequestBytesAndReturnsReply() throws IOException {
        String json = "{\"line_items\":[{\"item\":{\"id\":\"item_123\"},\"quantity\":2}]}";
        HttpCall call = new HttpCall("POST", uri("/echo"),
                Map.of("Idempotency-Key", "ucp-abc", "Request-Id", "r-1", "Content-Type", "application/json"),
                Optional.of(new FrozenBody(json)), Duration.ofSeconds(5));

        HttpReply reply = transport.send(call);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(reply.header("Content-Type")).contains("application/json");
        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("POST");
            assertThat(s.path()).isEqualTo("/echo");
            assertThat(s.headers().get("Idempotency-key")).containsExactly("ucp-abc");
            assertThat(s.headers().get("Request-id")).containsExactly("r-1");
            assertThat(s.body()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
        });
    }

    // Covers: 8.4 (a GET carries no body)
    @Test
    void send_getHasNoBody() throws IOException {
        transport.send(new HttpCall("GET", uri("/echo"), Map.of(), Optional.empty(), Duration.ofSeconds(5)));

        assertThat(seen).singleElement().satisfies(s -> {
            assertThat(s.method()).isEqualTo("GET");
            assertThat(s.body()).isEmpty();
        });
    }

    // Covers: 8.1 (redirects are never followed: a 3xx is a reply, and the Location is not requested)
    @Test
    void send_doesNotFollowRedirects() throws IOException {
        HttpReply reply = transport.send(
                new HttpCall("GET", uri("/redirect"), Map.of(), Optional.empty(), Duration.ofSeconds(5)));

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.header("Location")).contains("/echo");
        assertThat(requests).hasValue(1);
        assertThat(seen).isEmpty();
    }

    // Covers: 8.1, 8.3 (a request timeout surfaces as HttpTimeoutException, which FailureClassifier maps to Uncertain)
    @Test
    void send_throwsHttpTimeoutException_whenRequestTimesOut() {
        HttpCall call = new HttpCall("GET", uri("/slow"), Map.of(), Optional.empty(), Duration.ofMillis(200));

        assertThatThrownBy(() -> transport.send(call)).isInstanceOf(HttpTimeoutException.class);
    }

    // Covers: 8.1 (connection refused is a ConnectException; whether it is NotSent is decided by the raw-socket
    // test of decision A, not here)
    @Test
    void send_throwsConnectException_whenNothingListens() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        HttpCall call = new HttpCall("GET", URI.create("http://127.0.0.1:" + closedPort + "/x"), Map.of(),
                Optional.empty(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport.send(call)).isInstanceOf(ConnectException.class);
    }

    // Covers: 8.1 (a response over the size cap is an I/O failure, hence Uncertain; it is never read in full)
    @Test
    void send_throwsIOException_whenResponseExceedsCap() {
        UcpConfig d = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
        JdkHttpTransport small = new JdkHttpTransport(new UcpConfig(d.ucpAgent(), d.connectTimeout(), d.requestTimeout(),
                d.completeTimeout(), d.maxRetries(), d.backoffInitial(), d.backoffCap(), d.breakerThreshold(),
                d.breakerOpenFor(), 1024));
        HttpCall call = new HttpCall("GET", uri("/big"), Map.of(), Optional.empty(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> small.send(call)).isInstanceOf(IOException.class).hasMessageContaining("larger than");
    }

    // Covers: I8 (a call's toString never shows credentials in headers or the payment token in the body)
    @Test
    void httpCall_toString_hidesHeadersAndBody() {
        HttpCall call = new HttpCall("POST", uri("/echo"), Map.of("Authorization", "Bearer secret_canary"),
                Optional.of(new FrozenBody("{\"token\":\"tok_secret_canary\"}")), Duration.ofSeconds(5));

        assertThat(call.toString()).doesNotContain("canary").doesNotContain("Bearer").startsWith("HttpCall[POST ");
    }
}
