package dev.ucplab.service.cli;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One signed-in conversation with the service's REST API (m6-plan section 8): the dev sign-in, then calls that carry the
 * session cookie and the CSRF token, then sign-out. The cookie and the CSRF token live in this object's fields only,
 * never on disk and never in anything printed. {@code Origin} is the base URL, as a browser would send it.
 */
final class ApiSession implements AutoCloseable {

    /** An answer: the status, the JSON object body (empty when there is none) and one header's value on request. */
    record Reply(int status, Map<String, Object> body, Map<String, List<String>> headers) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String string(String key) {
            return body.get(key) instanceof String s ? s : "";
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String COOKIE_NAME = "ucplab_session";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final Consumer<String> secrets;
    private String cookie = "";
    private String csrf = "";

    /**
     * @param secrets told every secret this session handles (cookie, CSRF token), so a test can search the output for
     *     them; {@code s -> {}} in the program
     */
    ApiSession(String baseUrl, Consumer<String> secrets) {
        String trimmed = Objects.requireNonNull(baseUrl, "baseUrl");
        this.base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Signs {@code user} in with the local dev sign-in and reads the CSRF token from {@code /api/me}. */
    void signIn(String user) throws IOException, InterruptedException {
        Reply login = raw("POST", "/auth/dev-login", "{\"user\":" + quote(user) + "}", false);
        if (login.status() != 303) {
            throw new IOException("sign-in refused (" + login.status() + ")");
        }
        for (String setCookie : login.headers().getOrDefault("set-cookie", List.of())) {
            if (setCookie.startsWith(COOKIE_NAME + "=")) {
                cookie = setCookie.substring(COOKIE_NAME.length() + 1, setCookie.indexOf(';'));
                secrets.accept(cookie);
            }
        }
        if (cookie.isEmpty()) {
            throw new IOException("sign-in gave no session");
        }
        Reply me = raw("GET", "/api/me", null, true);
        csrf = me.headers().getOrDefault("x-csrf-token", List.of("")).getFirst();
        if (!me.ok() || csrf.isEmpty()) {
            throw new IOException("sign-in could not be confirmed");
        }
        secrets.accept(csrf);
    }

    Reply get(String path) throws IOException, InterruptedException {
        return raw("GET", path, null, true);
    }

    Reply post(String path, String json) throws IOException, InterruptedException {
        return raw("POST", path, json, true);
    }

    Reply put(String path, String json) throws IOException, InterruptedException {
        return raw("PUT", path, json, true);
    }

    Reply delete(String path) throws IOException, InterruptedException {
        return raw("DELETE", path, null, true);
    }

    /** Ends the session at the service. A failure is not reported: the session expires by itself. */
    @Override
    public void close() {
        if (cookie.isEmpty()) {
            return;
        }
        try {
            raw("POST", "/auth/logout", "{}", true);
        } catch (IOException e) {
            // Nothing more to do: nothing was written anywhere that outlives this process.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Reply raw(String method, String path, String body, boolean signedIn)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Origin", base).header("Accept", "application/json");
        if (signedIn) {
            request.header("Cookie", COOKIE_NAME + "=" + cookie);
        }
        if (!method.equals("GET")) {
            request.header("Content-Type", "application/json");
            if (signedIn) {
                request.header("X-CSRF-Token", csrf);
            }
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = Collections.emptyMap();
        String text = response.body().strip();
        if (!text.isEmpty()) {
            try {
                // A JSON array (the events route) is read as {"items": [...]}: the reader takes objects only.
                parsed = LenientJson.readObject(text.startsWith("[") ? "{\"items\":" + text + "}" : text);
            } catch (RuntimeException e) {
                parsed = Collections.emptyMap();
            }
        }
        return new Reply(response.statusCode(), parsed, response.headers().map());
    }

    /** A JSON string literal; the CLI writes only strings, so this is the only escaping it needs. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
