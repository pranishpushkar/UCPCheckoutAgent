package dev.ucplab.ucp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A test-only HTTP server that misbehaves on command (decision R: in M2 the faults the real merchant cannot produce come
 * from a stub; from M4 they go through the fault proxy). It serves the real discovery profile, records every request
 * with its headers and body, and plays a script of behaviours per "METHOD /path"; the last behaviour repeats.
 */
final class StubMerchant implements AutoCloseable {

    /** What the stub does with one request. */
    @FunctionalInterface
    interface Behaviour {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** A request as the stub saw it; header names are lower case. */
    record Seen(String method, String path, Map<String, String> headers, byte[] body) {}

    private final HttpServer server;
    private final Map<String, Deque<Behaviour>> script = new HashMap<>();
    private final List<Seen> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

    StubMerchant() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        on("GET /.well-known/ucp", ex -> reply(ex, 200, discovery()));
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    StubMerchant on(String route, Behaviour... behaviours) {
        synchronized (script) {
            script.computeIfAbsent(route, k -> new ArrayDeque<>()).clear();
            script.get(route).addAll(List.of(behaviours));
        }
        return this;
    }

    List<Seen> requestsTo(String route) {
        return seen.stream().filter(s -> (s.method() + " " + s.path()).equals(route)).toList();
    }

    private String discovery() throws IOException {
        try (InputStream in = StubMerchant.class.getResourceAsStream("/merchant/discovery.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("127.0.0.1:8081", "127.0.0.1:" + server.getAddress().getPort());
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body));
        Behaviour behaviour;
        synchronized (script) {
            Deque<Behaviour> steps = script.get(route);
            if (steps == null || steps.isEmpty()) {
                behaviour = e -> reply(e, 404, "{\"code\":\"not_found\",\"content\":\"stub has no route\"}");
            } else {
                behaviour = steps.size() > 1 ? steps.removeFirst() : steps.peekFirst();
            }
        }
        try {
            behaviour.handle(ex);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client gave up (a timeout test): nothing to do
        } finally {
            ex.close();
        }
    }

    // ---------------------------------------------------------------- behaviours

    static void reply(HttpExchange ex, int status, String body, String... headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            ex.getResponseHeaders().set(headers[i], headers[i + 1]);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Answers with a response body taken from a merchant fixture. */
    static Behaviour fixture(int status, String name, String... headers) {
        return ex -> {
            try (InputStream in = StubMerchant.class.getResourceAsStream("/merchant/" + name + ".json")) {
                reply(ex, status, new String(in.readAllBytes(), StandardCharsets.UTF_8), headers);
            }
        };
    }

    static Behaviour reply(int status, String body, String... headers) {
        return ex -> reply(ex, status, body, headers);
    }

    /** Takes the request and closes the connection without answering: the client sees an EOF. */
    static Behaviour drop() {
        return ex -> ex.close();
    }

    /** Holds the request longer than the client's timeout, then answers. */
    static Behaviour delay(long millis, Behaviour then) {
        return ex -> {
            Thread.sleep(millis);
            then.handle(ex);
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Test aid: every request seen, in order. */
    List<Seen> all() {
        return new ArrayList<>(seen);
    }
}
