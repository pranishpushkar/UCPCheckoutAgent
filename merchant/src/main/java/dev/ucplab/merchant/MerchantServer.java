package dev.ucplab.merchant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP adapter for the merchant. Uses only the JDK's built-in server so the project builds with just `javac`.
 *
 *   GET  /.well-known/ucp                         discovery profile
 *   POST /ucp/v1/checkout-sessions                create
 *   GET  /ucp/v1/checkout-sessions/{id}           get
 *   PUT  /ucp/v1/checkout-sessions/{id}           update (full replacement)
 *   POST /ucp/v1/checkout-sessions/{id}/complete  place the order
 *   POST /ucp/v1/checkout-sessions/{id}/cancel    cancel
 *   POST /ucp/v1/catalog/search                   catalog search (dev.ucp.shopping.catalog.search)
 *   POST /ucp/v1/catalog/lookup                   batch lookup by id (dev.ucp.shopping.catalog.lookup)
 *   POST /ucp/v1/catalog/product                  one product in full (dev.ucp.shopping.catalog.lookup)
 *   /_lab/*                                       test controls for the failure harness (not part of UCP)
 *
 * Deliberate gaps (see docs/spec-notes.md): plain HTTP, no authentication, no message signatures.
 */
final class MerchantServer {
    private final MerchantCore core;
    private final HttpServer server;
    private final CatalogApi catalogApi;

    MerchantServer(MerchantCore core, int port) throws IOException {
        this.core = core;
        this.catalogApi = new CatalogApi(core.catalog());
        // Loopback only: this is a lab server with test controls, so it should not be reachable from outside.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    void start() { server.start(); }

    void stop() { server.stop(0); }

    int port() { return server.getAddress().getPort(); }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (method.equals("GET") && path.equals("/.well-known/ucp")) {
                send(ex, 200, core.discovery("http://" + ex.getRequestHeaders().getFirst("Host")));
            } else if (path.startsWith("/ucp/v1/")) {
                ucp(ex, method, path.substring("/ucp/v1".length()), body);
            } else if (path.startsWith("/_lab/")) {
                lab(ex, method, path, body);
            } else {
                send(ex, 404, MerchantCore.protocolError("not_found", "No such route"));
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, MerchantCore.protocolError("invalid_request", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, MerchantCore.protocolError("internal_error", "Unexpected error"));
        } finally {
            ex.close();
        }
    }

    private void ucp(HttpExchange ex, String method, String path, String bodyText) throws IOException {
        var h = ex.getRequestHeaders();
        if (h.getFirst("Request-Id") == null || h.getFirst("UCP-Agent") == null) {
            send(ex, 400, MerchantCore.protocolError("missing_header", "Request-Id and UCP-Agent headers are required"));
            return;
        }
        if (path.startsWith("/catalog/")) {
            catalog(ex, method, path, bodyText);
            return;
        }

        boolean mutating = method.equals("POST") || method.equals("PUT");
        String key = h.getFirst("Idempotency-Key");
        if (mutating && key == null) {
            send(ex, 400, MerchantCore.protocolError("missing_header", "Idempotency-Key header is required"));
            return;
        }

        String[] seg = path.split("/"); // "/checkout-sessions/chk_1/complete" -> ["", "checkout-sessions", "chk_1", "complete"]
        if (seg.length < 2 || !seg[1].equals("checkout-sessions")) {
            send(ex, 404, MerchantCore.protocolError("not_found", "No such route"));
            return;
        }

        MerchantCore.Response r;
        if (seg.length == 2 && method.equals("POST")) {
            Map<String, Object> req = Json.parseObject(bodyText);
            r = core.idempotent("POST " + path, key, req, () -> core.create(req));
        } else if (seg.length == 3 && method.equals("GET")) {
            r = core.get(seg[2]);
        } else if (seg.length == 3 && method.equals("PUT")) {
            Map<String, Object> req = Json.parseObject(bodyText);
            r = core.idempotent("PUT " + path, key, req, () -> core.update(seg[2], req));
        } else if (seg.length == 4 && seg[3].equals("complete") && method.equals("POST")) {
            Map<String, Object> req = Json.parseObject(bodyText);
            r = core.idempotent("POST " + path, key, req, () -> core.complete(seg[2], req));
        } else if (seg.length == 4 && seg[3].equals("cancel") && method.equals("POST")) {
            Map<String, Object> req = Json.parseObject(bodyText);
            r = core.idempotent("POST " + path, key, req, () -> core.cancel(seg[2]));
        } else {
            send(ex, 404, MerchantCore.protocolError("not_found", "No such route"));
            return;
        }
        ex.getResponseHeaders().add("Request-Id", h.getFirst("Request-Id"));
        send(ex, r.status(), r.body());
    }

    /** The catalog reads: POST with a JSON body, no Idempotency-Key (they change nothing). */
    private void catalog(HttpExchange ex, String method, String path, String bodyText) throws IOException {
        Map<String, Object> req = Json.parseObject(bodyText);
        Map<String, Object> out = switch (method + " " + path) {
            case "POST /catalog/search" -> catalogApi.search(req);
            case "POST /catalog/lookup" -> catalogApi.lookup(req);
            case "POST /catalog/product" -> catalogApi.product(req);
            default -> null;
        };
        if (out == null) {
            send(ex, 404, MerchantCore.protocolError("not_found", "No such route"));
            return;
        }
        ex.getResponseHeaders().add("Request-Id", ex.getRequestHeaders().getFirst("Request-Id"));
        send(ex, 200, out);
    }

    private void lab(HttpExchange ex, String method, String path, String bodyText) throws IOException {
        Map<String, Object> req = Json.parseObject(bodyText);
        switch (method + " " + path) {
            case "GET /_lab/orders" -> {
                List<Map<String, Object>> orders = core.ordersJson();
                send(ex, 200, Map.of("count", orders.size(), "orders", orders));
            }
            case "GET /_lab/catalog" -> send(ex, 200, Map.of("items", core.catalogJson()));
            case "POST /_lab/reset" -> {
                core.reset();
                send(ex, 200, Map.of("ok", true));
            }
            case "POST /_lab/stock" -> {
                boolean ok = core.catalog().setStock(String.valueOf(req.get("item_id")), ((Number) req.get("stock")).intValue());
                send(ex, ok ? 200 : 404, Map.of("ok", ok));
            }
            case "POST /_lab/price" -> {
                boolean ok = core.catalog().setPrice(String.valueOf(req.get("item_id")), ((Number) req.get("price")).longValue());
                send(ex, ok ? 200 : 404, Map.of("ok", ok));
            }
            case "POST /_lab/config" -> {
                if (req.get("payment_latency_ms") instanceof Number n) core.payments().setLatencyMs(n.longValue());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("payment_latency_ms", core.payments().latencyMs());
                send(ex, 200, out);
            }
            default -> send(ex, 404, MerchantCore.protocolError("not_found", "No such lab route"));
        }
    }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        MerchantServer s = new MerchantServer(new MerchantCore(new Catalog(), new Payments(0)), port);
        s.start();
        System.out.println("UCP merchant listening on http://127.0.0.1:" + s.port());
        System.out.println("Discovery: http://127.0.0.1:" + s.port() + "/.well-known/ucp");
    }
}
