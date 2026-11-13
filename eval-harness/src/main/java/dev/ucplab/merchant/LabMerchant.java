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
import java.util.Optional;
import java.util.UUID;

/**
 * Starts the reference merchant in process for one eval lane, and reads its ground truth (13.1, A.3). It lives in the
 * merchant's package only to reach the package-private {@code MerchantServer}; nothing in {@code merchant/src} changes.
 * Every read and control goes over HTTP straight to the merchant, never through the fault proxy, so what the harness
 * reads is what the merchant really did. This is the only class of the harness that touches {@code dev.ucplab.merchant}.
 */
public final class LabMerchant implements AutoCloseable {

    /** The UCP-Agent of the harness's own ground-truth reads, so they can never be mistaken for the agent's. */
    static final String GROUND_TRUTH_AGENT = "profile=\"https://eval.ucplab.dev/ground-truth\"";

    /** The UCP version the merchant speaks, for the report's configuration block. */
    public static final String UCP_VERSION = MerchantCore.VERSION;

    private final MerchantServer server;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();

    private LabMerchant(MerchantServer server) {
        this.server = server;
    }

    /** A fresh merchant (default catalog, no payment latency) on 127.0.0.1, an ephemeral port. */
    public static LabMerchant start() throws IOException {
        MerchantServer server = new MerchantServer(new MerchantCore(new Catalog(), new Payments(0)), 0);
        server.start();
        return new LabMerchant(server);
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /** {@code GET /_lab/orders}: every order the merchant created, as {@code {id, checkout_id, buyer_email, amount}}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders() {
        String body = call("GET", "/_lab/orders", Optional.empty(), false, 200).orElseThrow();
        return (List<Map<String, Object>>) (List<?>) LenientJson.readObject(body).get("orders");
    }

    /**
     * The merchant's own {@code GET /ucp/v1/checkout-sessions/{id}}, read directly (evidence G): the session's lines,
     * currency and status, which {@code /_lab/orders} does not carry. Empty if the merchant has no such session.
     */
    public Optional<Map<String, Object>> session(String sessionId) {
        if (!sessionId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("not a session id: " + sessionId);
        }
        return call("GET", "/ucp/v1/checkout-sessions/" + sessionId, Optional.empty(), true, 200, 404)
                .map(LenientJson::readObject);
    }

    /** {@code POST /_lab/reset}. Session and order numbers keep counting, so a stale request can never hit a new run. */
    public void reset() {
        call("POST", "/_lab/reset", Optional.of("{}"), 200);
    }

    public void setStock(String itemId, int stock) {
        call("POST", "/_lab/stock", Optional.of("{\"item_id\":\"" + itemId + "\",\"stock\":" + stock + "}"), 200);
    }

    public void setPrice(String itemId, long priceMinor) {
        call("POST", "/_lab/price", Optional.of("{\"item_id\":\"" + itemId + "\",\"price\":" + priceMinor + "}"), 200);
    }

    public void setPaymentLatencyMs(long millis) {
        call("POST", "/_lab/config", Optional.of("{\"payment_latency_ms\":" + millis + "}"), 200);
    }

    /** The body for a 200, empty for any other accepted status; anything else is an error. */
    private Optional<String> call(String method, String path, Optional<String> body, int... accepted) {
        return call(method, path, body, false, accepted);
    }

    /** {@code ucp}: send the headers every UCP request carries (8.4), with a Request-Id of its own. */
    private Optional<String> call(String method, String path, Optional<String> body, boolean ucp, int... accepted) {
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(baseUrl().resolve(path)).timeout(Duration.ofSeconds(5));
            r.method(method, body.map(HttpRequest.BodyPublishers::ofString)
                    .orElseGet(HttpRequest.BodyPublishers::noBody));
            body.ifPresent(b -> r.header("Content-Type", "application/json"));
            if (ucp) {
                r.header("UCP-Agent", GROUND_TRUTH_AGENT).header("Request-Id", UUID.randomUUID().toString());
            }
            HttpResponse<byte[]> response = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
            for (int status : accepted) {
                if (response.statusCode() == status) {
                    return status == 200
                            ? Optional.of(new String(response.body(), StandardCharsets.UTF_8))
                            : Optional.empty();
                }
            }
            throw new IllegalStateException(method + " " + path + " answered " + response.statusCode());
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop();
        http.shutdownNow();
    }
}
