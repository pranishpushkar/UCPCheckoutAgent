package dev.ucplab.service.lab;

import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.service.config.ServiceProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The merchant's own lab routes (12, 24.3), read directly and never through the proxy: ground-truth orders, the reset,
 * and a price change. Local profile only (23.3).
 */
@Component
@Profile("local")
public class MerchantLab {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public MerchantLab(ServiceProperties properties) {
        this.base = properties.merchantLabUrl();
    }

    /** {@code GET /_lab/orders}: the orders the merchant really created, as it reports them. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders() {
        return (List<Map<String, Object>>) (List<?>) LenientJson.readObject(call("GET", "/_lab/orders", null))
                .get("orders");
    }

    /** {@code POST /_lab/reset}: back to the merchant's initial catalog and no orders. */
    public void reset() {
        call("POST", "/_lab/reset", null);
    }

    /** {@code POST /_lab/price}: sets one item's price in minor units. */
    public void setPrice(String itemId, long priceMinor) {
        call("POST", "/_lab/price", CanonicalJson.write(Map.of("item_id", itemId, "price", priceMinor)));
    }

    private String call(String method, String path, String body) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT);
            r.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (body != null) {
                r.header("Content-Type", "application/json");
            }
            HttpResponse<byte[]> response = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("the merchant lab answered " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("the merchant is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
