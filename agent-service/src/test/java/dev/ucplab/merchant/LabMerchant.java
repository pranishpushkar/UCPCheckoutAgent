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
 * The reference merchant in this JVM on a free loopback port, for agent-service tests (m6-plan section 2.4). It lives
 * in the merchant's package only to reach the package-private server classes; nothing in {@code merchant/src} is
 * changed. Lab reads go straight to the merchant, never through the proxy, so they are the merchant's ground truth.
 */
public final class LabMerchant implements AutoCloseable {

    private final MerchantServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private LabMerchant(MerchantServer server) {
        this.server = server;
    }

    public static LabMerchant start() throws IOException {
        MerchantServer server = new MerchantServer(new MerchantCore(new Catalog(), new Payments(0)), 0);
        server.start();
        return new LabMerchant(server);
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /** {@code GET /_lab/orders}: the orders the merchant really created. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders() {
        return (List<Map<String, Object>>) (List<?>) LenientJson.readObject(lab("GET", "/_lab/orders", null))
                .get("orders");
    }

    private String lab(String method, String path, String body) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(baseUrl().resolve(path)).timeout(Duration.ofSeconds(5));
            r.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
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
