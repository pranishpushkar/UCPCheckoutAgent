package dev.ucplab.service;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plain HTTP against the running service, the way a browser or the CLI would call it: no redirects followed, cookies
 * and headers set by hand. Every response is kept, so a test can search all of them (the token search of C11).
 */
final class ApiClient {

    static final String ORIGIN = "http://localhost:8080";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final URI base;
    final List<HttpResponse<String>> seen = Collections.synchronizedList(new ArrayList<>());

    ApiClient(int port) {
        base = URI.create("http://127.0.0.1:" + port);
    }

    /** A signed-in browser: its cookie value and the CSRF token {@code /api/me} gave it. */
    record Session(String cookie, String csrf) {}

    HttpResponse<String> send(String method, String path, Optional<String> body, String... headers) {
        HttpRequest.Builder r = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .method(method, body.map(HttpRequest.BodyPublishers::ofString)
                        .orElse(HttpRequest.BodyPublishers.noBody()));
        if (headers.length > 0) {
            r.headers(headers);
        }
        try {
            HttpResponse<String> response = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            seen.add(response);
            return response;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> get(String path, String... headers) {
        return send("GET", path, Optional.empty(), headers);
    }

    HttpResponse<String> get(Session s, String path) {
        return get(path, "Cookie", "ucplab_session=" + s.cookie());
    }

    /** A JSON POST with the session's cookie and CSRF token. */
    HttpResponse<String> post(Session s, String path, String json) {
        return send("POST", path, Optional.of(json), "Cookie", "ucplab_session=" + s.cookie(), "X-CSRF-Token", s.csrf(),
                "Content-Type", "application/json");
    }

    /** {@code POST /auth/dev-login} as JSON from the configured origin, carrying {@code cookie} if there is one. */
    HttpResponse<String> devLogin(String user, Optional<String> returnTo, Optional<String> cookie) {
        String json = "{\"user\":\"" + user + "\"" + returnTo.map(r -> ",\"return_to\":\"" + r + "\"").orElse("")
                + "}";
        List<String> headers = new ArrayList<>(List.of("Origin", ORIGIN, "Content-Type", "application/json"));
        cookie.ifPresent(c -> headers.addAll(List.of("Cookie", "ucplab_session=" + c)));
        return send("POST", "/auth/dev-login", Optional.of(json), headers.toArray(String[]::new));
    }

    /** Signs {@code user} in and reads their CSRF token from {@code /api/me}. */
    Session signIn(String user) {
        return signIn(user, Optional.empty());
    }

    Session signIn(String user, Optional<String> oldCookie) {
        HttpResponse<String> login = devLogin(user, Optional.empty(), oldCookie);
        if (login.statusCode() != 303) {
            throw new AssertionError("sign-in answered " + login.statusCode());
        }
        String cookie = cookieValue(login).orElseThrow();
        HttpResponse<String> me = get("/api/me", "Cookie", "ucplab_session=" + cookie);
        return new Session(cookie, me.headers().firstValue("X-CSRF-Token").orElseThrow());
    }

    static Optional<String> setCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("ucplab_session="))
                .findFirst();
    }

    static Optional<String> cookieValue(HttpResponse<?> response) {
        return setCookie(response).map(c -> c.substring("ucplab_session=".length(), c.indexOf(';')));
    }

    static Map<String, Object> json(HttpResponse<String> response) {
        return LenientJson.readObject(response.body());
    }
}
