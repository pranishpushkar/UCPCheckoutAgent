package dev.ucplab.faultproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The fault-injection proxy (§12 [CONTRACT]): a reverse proxy on 127.0.0.1 in front of one loopback merchant. Requests
 * are forwarded with their headers (except hop-by-hop ones), and every one is recorded in the request log with its
 * {@code Idempotency-Key} and {@code bodyHmac}. Paths under {@code /_proxy/} are the admin API and are never forwarded;
 * {@code /_lab/} is refused with 404.
 *
 * <p>The HMAC key is given at start-up and kept only in memory: no method returns it, nothing logs it, and the admin API
 * can neither read nor set it (§12 Body hash).
 *
 * <p>No lock of any kind: the rule set is one atomic reference and the log is a concurrent map, so no request ever
 * waits on another and nothing is held across network I/O (7.8). Each request runs on its own virtual thread.
 */
public final class FaultProxy implements AutoCloseable {

    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    static final String ALL_METHOD_RETRY = "jdk.httpclient.enableAllMethodRetry";
    private static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    /** Longer than any hold a rule can ask for in M4, so the proxy itself never times out first. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /** Request headers the proxy does not copy: hop-by-hop ones and those the JDK client sets itself. */
    private static final Set<String> REQUEST_HEADERS_NOT_COPIED = Set.of("host", "content-length", "connection",
            "expect", "upgrade", "transfer-encoding", "te", "trailer", "keep-alive");
    /** Response headers the proxy does not copy: hop-by-hop ones and those the JDK server sets itself. */
    private static final Set<String> RESPONSE_HEADERS_NOT_COPIED = Set.of("content-length", "connection",
            "transfer-encoding", "te", "trailer", "keep-alive", "upgrade", "date");

    private final URI upstream;
    private final SecretKeySpec hmacKey;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient client;
    private final RequestLog log;
    private final AtomicReference<Matcher> matcher = new AtomicReference<>(new Matcher(RuleSet.empty()));
    private final AdminApi admin;

    private FaultProxy(URI upstream, int port, byte[] hmacKey, Clock clock) throws IOException {
        this.upstream = upstream;
        this.hmacKey = new SecretKeySpec(hmacKey, HMAC);
        this.log = new RequestLog(clock);
        this.admin = new AdminApi(this);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@link #start(URI, int, byte[], Clock)} with the system clock. */
    public static FaultProxy start(URI upstream, int port, byte[] hmacKey) throws IOException {
        return start(upstream, port, hmacKey, Clock.systemUTC());
    }

    /**
     * Starts a proxy on 127.0.0.1:{@code port} (0 picks a free port) in front of {@code upstream}.
     *
     * @param hmacKey 32 random bytes for {@code bodyHmac}; copied, so the caller may wipe its array
     * @throws IllegalStateException if {@code jdk.httpclient.enableAllMethodRetry} is true (or set empty, which the JDK
     *     also reads as true): the JDK could then send a POST to the merchant twice without the log showing it
     * @throws IllegalArgumentException if the upstream is not a plain {@code http://} URL on a loopback address, or the
     *     key is not 32 bytes
     */
    public static FaultProxy start(URI upstream, int port, byte[] hmacKey, Clock clock) throws IOException {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(hmacKey, "hmacKey");
        Objects.requireNonNull(clock, "clock");
        String retryAll = System.getProperty(ALL_METHOD_RETRY);
        if (retryAll != null && (retryAll.isEmpty() || Boolean.parseBoolean(retryAll))) {
            throw new IllegalStateException(ALL_METHOD_RETRY + " must not be true: the JDK would re-send a POST by"
                    + " itself, and the proxy log would not show it");
        }
        if (hmacKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("the bodyHmac key must be " + KEY_BYTES + " bytes");
        }
        return new FaultProxy(checkUpstream(upstream), port, hmacKey.clone(), clock);
    }

    private static URI checkUpstream(URI upstream) throws IOException {
        if (!"http".equals(upstream.getScheme()) || upstream.getHost() == null || upstream.getRawUserInfo() != null
                || upstream.getRawQuery() != null || upstream.getRawFragment() != null
                || !(upstream.getRawPath() == null || upstream.getRawPath().isEmpty() || upstream.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("upstream must be http://<loopback host>[:port] with no path: " + upstream);
        }
        for (InetAddress address : InetAddress.getAllByName(upstream.getHost())) {
            if (!address.isLoopbackAddress()) {
                throw new IllegalArgumentException("upstream must be a loopback address (§12 Safety): " + upstream);
            }
        }
        return URI.create("http://" + upstream.getRawAuthority());
    }

    /** {@code http://127.0.0.1:<port>}. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    /** Replaces the rule set; every counter starts again from zero. Requests in flight keep the set they started with. */
    public void install(RuleSet rules) {
        matcher.set(new Matcher(Objects.requireNonNull(rules, "rules")));
    }

    /** Installs the empty rule set ({@code DELETE /_proxy/rules}). */
    public void clearRules() {
        install(RuleSet.empty());
    }

    /** The log entries with {@code seq > after}, in order ({@code GET /_proxy/log?after=}). */
    public List<LogEntry> log(long after) {
        return log.after(after);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        client.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (RuntimeException e) {
            // A bug in the proxy must not look like a fault the rules asked for: answer 500 if nothing was sent yet.
            if (ex.getResponseCode() == -1) {
                respond(ex, 500, "{\"error\":\"proxy_error\"}");
            }
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("CONNECT")) {
            respond(ex, 405, "{\"error\":\"connect_refused\"}");
            return;
        }
        URI target = ex.getRequestURI();
        String rawPath = target.getRawPath();
        if (target.isAbsolute() || target.getRawAuthority() != null || rawPath == null || !rawPath.startsWith("/")) {
            respond(ex, 400, "{\"error\":\"origin_form_only\"}");
            return;
        }
        if (!isPlainPath(rawPath)) {
            respond(ex, 400, "{\"error\":\"path_not_allowed\"}");
            return;
        }
        // Checked on the raw and the decoded path, so /%5flab/orders cannot reach the merchant's /_lab context.
        String raw = rawPath.toLowerCase(Locale.ROOT);
        String decoded = target.getPath().toLowerCase(Locale.ROOT);
        if (under(raw, "/_proxy") || under(decoded, "/_proxy")) {
            admin.handle(ex, rawPath, target.getRawQuery());
            return;
        }
        if (under(raw, "/_lab") || under(decoded, "/_lab")) {
            respond(ex, 404, "{\"error\":\"not_found\"}");
            return;
        }
        proxy(ex, rawPath, target.getRawQuery());
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /**
     * No dot segments and no encoded dot, slash or backslash, so the merchant can never resolve a path that this proxy
     * did not see as written (for example {@code /ucp/../_lab/orders}).
     */
    private static boolean isPlainPath(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("\\")) {
            return false;
        }
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private void proxy(HttpExchange ex, String rawPath, String rawQuery) throws IOException {
        byte[] body = readBody(ex);
        if (body.length > MAX_BODY_BYTES) {
            respond(ex, 413, "{\"error\":\"request_too_large\"}");
            return;
        }
        String method = ex.getRequestMethod();
        LogEntry entry = log.open(method, rawPath, header(ex, "Request-Id"), header(ex, "Idempotency-Key"),
                bodyHmac(body), Optional.empty(), Optional.empty());
        long seq = entry.seq();
        UpstreamReply reply;
        try {
            reply = forward(ex, seq, method, rawPath, rawQuery, body);
        } catch (IOException e) {
            log.update(seq, LogEntry::withUpstreamError);
            return;
        }
        sendReply(ex, seq, reply);
    }

    /** The merchant's complete reply to one forwarded copy. */
    record UpstreamReply(int status, Map<String, List<String>> headers, byte[] body) {
    }

    /** Sends one copy of the request to the merchant and reads the whole reply; counts the copy in the log. */
    private UpstreamReply forward(HttpExchange ex, long seq, String method, String rawPath, String rawQuery,
                                  byte[] body) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(upstream + rawPath + (rawQuery == null ? "" : "?" + rawQuery)))
                .timeout(REQUEST_TIMEOUT)
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        ex.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!REQUEST_HEADERS_NOT_COPIED.contains(lower) && !lower.startsWith("proxy-")) {
                values.forEach(v -> request.header(name, v));
            }
        });
        HttpRequest built = request.build();
        log.update(seq, LogEntry::withForward);
        try {
            HttpResponse<InputStream> response = client.send(built, HttpResponse.BodyHandlers.ofInputStream());
            byte[] out;
            try (InputStream in = response.body()) {
                out = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (out.length > MAX_BODY_BYTES) {
                throw new IOException("merchant response larger than " + MAX_BODY_BYTES + " bytes");
            }
            log.update(seq, e -> e.withMerchantStatus(response.statusCode()));
            if (method.equals("GET") && rawPath.equals("/.well-known/ucp")) {
                out = new String(out, StandardCharsets.UTF_8)
                        .replace(upstream.getRawAuthority(), baseUrl().getRawAuthority())
                        .getBytes(StandardCharsets.UTF_8);
            }
            return new UpstreamReply(response.statusCode(), response.headers().map(), out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException io = new InterruptedIOException("interrupted while forwarding");
            io.initCause(e);
            throw io;
        }
    }

    /** Sends the merchant's reply to the client; a client that is no longer there is logged as {@code clientGone}. */
    private void sendReply(HttpExchange ex, long seq, UpstreamReply reply) {
        try {
            reply.headers().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!RESPONSE_HEADERS_NOT_COPIED.contains(lower) && !lower.startsWith("proxy-") && !lower.startsWith(":")) {
                    ex.getResponseHeaders().put(name, List.copyOf(values));
                }
            });
            boolean noBody = reply.body().length == 0 || ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(reply.status(), noBody ? -1 : reply.body().length);
            if (!noBody) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(reply.body());
                }
            }
        } catch (IOException e) {
            log.update(seq, LogEntry::withClientGone);
        }
    }

    private Optional<String> bodyHmac(byte[] body) {
        if (body.length == 0) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hmacKey);
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(body)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return in.readNBytes(MAX_BODY_BYTES + 1);
        }
    }

    private static Optional<String> header(HttpExchange ex, String name) {
        return Optional.ofNullable(ex.getRequestHeaders().getFirst(name));
    }

    static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
