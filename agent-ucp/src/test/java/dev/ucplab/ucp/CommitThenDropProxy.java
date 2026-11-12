package dev.ucplab.ucp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A test-only forwarding proxy in front of the real merchant. While {@link #dropCreates} is set it forwards a create to
 * the merchant, so the merchant commits it, and then closes the connection without answering: the client sees an EOF
 * (an Uncertain outcome) for a request that did take effect. Everything else is relayed unchanged, except that the
 * discovery profile is rewritten to point at the proxy, as the gateway requires the checkout endpoint on its own host.
 */
final class CommitThenDropProxy implements AutoCloseable {

    private static final Set<String> NOT_COPIED = Set.of("host", "content-length", "connection", "expect", "upgrade", "transfer-encoding");

    private final HttpServer server;
    private final URI merchant;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicBoolean dropCreates = new AtomicBoolean();

    CommitThenDropProxy(URI merchant) throws IOException {
        this.merchant = merchant;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::relay);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void dropCreates(boolean drop) {
        dropCreates.set(drop);
    }

    private void relay(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String path = ex.getRequestURI().getRawPath();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(merchant.toString().replaceAll("/+$", "") + path
                        + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())))
                .method(ex.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
        ex.getRequestHeaders().forEach((k, v) -> {
            if (!NOT_COPIED.contains(k.toLowerCase())) {
                v.forEach(value -> request.header(k, value));
            }
        });
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.close();
            return;
        }
        boolean create = ex.getRequestMethod().equals("POST") && path.endsWith("/checkout-sessions");
        if (create && dropCreates.get()) {
            ex.close();
            return;
        }
        byte[] out = response.body();
        if (path.equals("/.well-known/ucp")) {
            out = new String(out, StandardCharsets.UTF_8)
                    .replace(merchant.getAuthority(), baseUrl().getAuthority()).getBytes(StandardCharsets.UTF_8);
        }
        response.headers().firstValue("content-type").ifPresent(t -> ex.getResponseHeaders().set("Content-Type", t));
        ex.sendResponseHeaders(response.statusCode(), out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
