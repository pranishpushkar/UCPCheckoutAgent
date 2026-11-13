package dev.ucplab.faultproxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** One echo upstream and one proxy in front of it per test, plus a plain HTTP/1.1 client. */
abstract class ProxyTestBase {

    /** A fixed key is fine in tests: what matters here is that the proxy uses the key it was given. */
    static final byte[] KEY = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static final String KEY_HEX = HexFormat.of().formatHex(KEY);

    EchoUpstream upstream;
    FaultProxy proxy;
    HttpClient client;

    @BeforeEach
    void startProxy() throws IOException {
        upstream = new EchoUpstream();
        proxy = FaultProxy.start(upstream.baseUrl(), 0, KEY);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void stopProxy() {
        client.shutdownNow();
        proxy.close();
        upstream.close();
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        return send(method, path, body, headers, Duration.ofSeconds(10));
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(proxy.baseUrl() + path)).timeout(timeout)
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send("GET", path, "", Map.of());
    }

    HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send("POST", path, body, Map.of("Content-Type", "application/json"));
    }

    String adminLog() throws IOException, InterruptedException {
        return get("/_proxy/log").body();
    }

    /** Writes {@code head} as raw bytes and returns everything the proxy sends back ("" if it just closes). */
    String raw(String head) throws IOException {
        try (Socket s = new Socket("127.0.0.1", proxy.port())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            try {
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (SocketTimeoutException e) {
                return "<timeout>";
            }
        }
    }

    static String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
