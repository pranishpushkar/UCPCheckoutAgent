package dev.ucplab.faultproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A counting upstream for the proxy tests: it records every request that reaches it and answers with a small JSON echo,
 * or with whatever a test's {@link Responder} sends. It stands in for the merchant so the proxy is tested on its own.
 */
final class EchoUpstream implements AutoCloseable {

    record Arrival(int n, String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse("");
        }
    }

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange ex, Arrival arrival) throws IOException;
    }

    private final HttpServer server;
    private final List<Arrival> arrivals = new CopyOnWriteArrayList<>();
    private final AtomicInteger count = new AtomicInteger();
    private volatile Responder responder = EchoUpstream::echo;

    EchoUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String authority() {
        return baseUrl().getRawAuthority();
    }

    void respondWith(Responder r) {
        responder = r;
    }

    List<Arrival> arrivals() {
        return List.copyOf(arrivals);
    }

    long count(String method, String path) {
        return arrivals.stream().filter(a -> a.method().equals(method) && a.path().equals(path)).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
            Arrival a = new Arrival(count.incrementAndGet(), ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                    ex.getRequestURI().getRawQuery(), headers, ex.getRequestBody().readAllBytes());
            arrivals.add(a);
            responder.respond(ex, a);
        } finally {
            ex.close();
        }
    }

    static void echo(HttpExchange ex, Arrival a) throws IOException {
        send(ex, 200, "{\"method\":\"" + a.method() + "\",\"path\":\"" + a.path() + "\",\"n\":" + a.n() + "}");
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
