package dev.ucplab.merchant;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Starts the reference merchant in this JVM on a free port, for contract tests (section 14, level 4). It lives in the
 * merchant's package only to reach its package-private classes; nothing in {@code merchant/src} is changed. All lab
 * controls go through HTTP ({@code /_lab/*}, A.3), so what a test reads is the merchant's ground truth, not a shortcut.
 */
public final class MerchantHarness implements AutoCloseable {

    private final MerchantServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private MerchantHarness(MerchantServer server) {
        this.server = server;
    }

    /** A fresh merchant (default catalog, no payment latency) on an ephemeral loopback port. */
    public static MerchantHarness start() throws IOException {
        MerchantServer server = new MerchantServer(new MerchantCore(new Catalog(), new Payments(0)), 0);
        server.start();
        return new MerchantHarness(server);
    }

    public int port() {
        return server.port();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port());
    }

    /** {@code GET /_lab/orders}: the orders the merchant really created (ground truth for I1 and I2). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders() {
        return (List<Map<String, Object>>) (List<?>) LenientJson.readObject(lab("GET", "/_lab/orders", null)).get("orders");
    }

    /** {@code POST /_lab/reset}: stock, prices, sessions, orders, idempotency keys and latency back to the start. */
    public void reset() {
        lab("POST", "/_lab/reset", "{}");
    }

    public void setStock(String itemId, int stock) {
        lab("POST", "/_lab/stock", "{\"item_id\":\"" + itemId + "\",\"stock\":" + stock + "}");
    }

    public void setPrice(String itemId, long priceMinor) {
        lab("POST", "/_lab/price", "{\"item_id\":\"" + itemId + "\",\"price\":" + priceMinor + "}");
    }

    /** Holds every charge for this long, so a checkout stays in {@code complete_in_progress} meanwhile (A.3). */
    public void setPaymentLatencyMs(long millis) {
        lab("POST", "/_lab/config", "{\"payment_latency_ms\":" + millis + "}");
    }

    private String lab(String method, String path, String body) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(baseUrl().resolve(path)).timeout(Duration.ofSeconds(5));
            r.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<byte[]> response = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("lab " + path + " answered " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }
}
