package dev.ucplab.ucp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HttpTransport} over {@code java.net.http.HttpClient} (8.3, 8.4). HTTP/1.1, and redirects are never followed
 * (8.1): a 3xx is a reply like any other status. A response larger than {@link UcpConfig#maxResponseBytes()} is an
 * {@link IOException}.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;
    private final int maxResponseBytes;

    public JdkHttpTransport(UcpConfig config) {
        Objects.requireNonNull(config, "config");
        this.maxResponseBytes = config.maxResponseBytes();
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(config.connectTimeout())
                .build();
    }

    @Override
    public HttpReply send(HttpCall call) throws IOException {
        Objects.requireNonNull(call, "call");
        HttpRequest.Builder request = HttpRequest.newBuilder(call.uri()).timeout(call.timeout());
        call.headers().forEach(request::header);
        HttpRequest.BodyPublisher publisher = call.body()
                .map(b -> HttpRequest.BodyPublishers.ofByteArray(b.bytes()))
                .orElseGet(HttpRequest.BodyPublishers::noBody);
        request.method(call.method(), publisher);
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(maxResponseBytes + 1);
                if (body.length > maxResponseBytes) {
                    throw new IOException("response body larger than " + maxResponseBytes + " bytes");
                }
                return new HttpReply(response.statusCode(), headersOf(response), body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException io = new InterruptedIOException("interrupted while calling " + call.uri().getPath());
            io.initCause(e);
            throw io;
        }
    }

    private static Map<String, List<String>> headersOf(HttpResponse<?> response) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) ->
                out.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(values)));
        return out;
    }
}
