package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.service.ApiClient.Session;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk 9 (m6-plan section 16, 23.5): every error, whatever raised it, is {@code {code, message, request_id}} with
 * {@code request_id} equal to the {@code X-Request-Id} header, and none shows a stack trace, an exception's message,
 * a class name or a Whitelabel page.
 */
class ErrorShapeTest {

    /** Planted in a thrown exception's message, so a leak of the message is visible. */
    static final String CANARY = "CANARY-risk9-exception-message";

    /** A route that fails the way a bug would. Test scope only. */
    @RestController
    public static class ThrowingController {
        @GetMapping("/api/test/boom")
        String boom() {
            throw new IllegalStateException(CANARY + " at dev.ucplab.Secret.method(Secret.java:1)");
        }

        @GetMapping("/test/boom")
        String boomOutsideApi() {
            throw new UnsupportedOperationException(CANARY);
        }
    }

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;
    private Session alice;

    @BeforeEach
    void start() throws IOException {
        service = TestService.start(dir, Map.of(), Optional.empty(), List.of(ThrowingController.class));
        api = new ApiClient(service.port());
        alice = api.signIn("alice");
    }

    @AfterEach
    void stop() {
        service.close();
    }

    private static void assertShape(String what, HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(what).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).as(what)
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        Map<String, Object> body = json(r);
        assertThat(body.keySet()).as(what).containsExactly("code", "message", "request_id");
        assertThat(body).as(what).containsEntry("code", code);
        assertThat(r.headers().firstValue("X-Request-Id")).as(what).contains((String) body.get("request_id"));
        assertThat((String) body.get("request_id")).as(what + ": a UUIDv7")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(r.body()).as(what).doesNotContain(CANARY).doesNotContain("Exception").doesNotContain("Whitelabel")
                .doesNotContain("java.").doesNotContain("dev.ucplab").doesNotContain("\tat ").doesNotContain("trace")
                .doesNotContain("<html");
    }

    // Covers: risk 9, 23.5 (an unknown route, with or without a session, under /api and outside it: 404 not_found)
    @Test
    void unknownRoute_is404Shape() {
        assertShape("api, signed in", api.get(alice, "/api/nope"), 404, "not_found");
        assertShape("api, anonymous", api.get("/api/nope"), 404, "not_found");
        assertShape("outside api", api.get("/nope"), 404, "not_found");
        assertShape("internal recover, not registered in M6", api.send("POST", "/internal/recover",
                Optional.of("{}"), "Content-Type", "application/json"), 404, "not_found");
    }

    // Covers: risk 9, 23.5 (a bad JSON body is 422 validation_failed)
    @Test
    void badJson_is422Shape() {
        HttpResponse<String> r = api.send("POST", "/auth/dev-login", Optional.of("{\"user\": " + CANARY),
                "Origin", ApiClient.ORIGIN, "Content-Type", "application/json");

        assertShape("bad JSON", r, 422, "validation_failed");
    }

    // Covers: risk 9, 23.5 (a content type the route does not take is 422 validation_failed)
    @Test
    void wrongContentType_is422Shape() {
        HttpResponse<String> r = api.send("POST", "/auth/dev-login", Optional.of("user=" + CANARY),
                "Origin", ApiClient.ORIGIN, "Content-Type", "text/plain");

        assertShape("text/plain", r, 422, "validation_failed");
    }

    // Covers: risk 9, 23.5, I8 (a thrown RuntimeException is 500 internal with a fixed message, inside /api and outside)
    @Test
    void thrownRuntimeException_is500Shape() {
        assertShape("api", api.get(alice, "/api/test/boom"), 500, "internal");
        assertShape("outside api", api.get("/test/boom"), 500, "internal");
    }

    // Covers: risk 9 (a method the route does not have is 404 not_found, not a 405 page)
    @Test
    void wrongMethod_is404Shape() {
        assertShape("DELETE /healthz", api.send("DELETE", "/healthz", Optional.empty()), 404, "not_found");
    }

    // Covers: risk 9 (a request Tomcat rejects before any servlet runs, here an encoded slash, never reaches Spring, so
    // it gets Tomcat's own minimal page rather than the JSON shape; that page still leaks nothing: no stack trace,
    // exception, class, server name or version. Recorded as a known gap in m6-progress3.md)
    @Test
    void containerRejection_leaksNothing() {
        HttpResponse<String> r = api.get("/api/a%2Fb");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("java.").doesNotContain("org.apache")
                .doesNotContain("Tomcat").doesNotContain("\tat ").doesNotContain("Whitelabel").doesNotContain("a%2Fb");
        assertThat(r.headers().firstValue("Server")).isEmpty();
    }

    // Covers: risk 9, 23.5 (each error message is the code's fixed text, never input: every code's message, collected)
    @Test
    void messages_areFixedTexts() {
        Map<String, String> messages = new LinkedHashMap<>();
        for (HttpResponse<String> r : List.of(api.get("/api/nope"), api.get("/api/me"),
                api.send("POST", "/auth/logout", Optional.of("{}"), "Cookie", "ucplab_session=" + alice.cookie()),
                api.get(alice, "/api/test/boom"))) {
            Map<String, Object> body = json(r);
            messages.put((String) body.get("code"), (String) body.get("message"));
        }

        assertThat(messages).containsExactly(Map.entry("not_found", "not found"),
                Map.entry("unauthenticated", "sign in first"), Map.entry("csrf_failed", "missing or wrong CSRF token"),
                Map.entry("internal", "internal error"));
    }
}
