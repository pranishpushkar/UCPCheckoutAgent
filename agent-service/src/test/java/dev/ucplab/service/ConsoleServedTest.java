package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The console as served (24.1, m6-plan section 7, C18): the page at {@code /} and at {@code /intents/{id}}, its two
 * files, the Content-Security-Policy, {@code no-cache} (never {@code no-store}, never a max-age: 20), and a plain JSON
 * 404 for every other path, because Spring's own resource handling is off.
 */
class ConsoleServedTest {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; "
            + "base-uri 'none'; frame-ancestors 'none'";

    @TempDir
    Path dir;

    private TestService service;
    private ApiClient api;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private void start() throws IOException {
        service = TestService.start(dir);
        api = new ApiClient(service.port());
    }

    private static void assertConsoleHeaders(HttpResponse<String> r, String what) {
        assertThat(r.statusCode()).as(what).isEqualTo(200);
        assertThat(r.headers().allValues("Cache-Control")).as("Cache-Control of " + what).containsExactly("no-cache");
        assertThat(r.headers().firstValue("ETag")).as("ETag of " + what).isEmpty();
        assertThat(r.headers().firstValue("Last-Modified")).as("Last-Modified of " + what).isEmpty();
        assertThat(r.headers().firstValue("Content-Security-Policy")).as("CSP of " + what).contains(CSP);
        assertThat(r.headers().firstValue("X-Content-Type-Options")).as("nosniff of " + what).contains("nosniff");
    }

    // Covers: 24.1, 20 (/ serves index.html with the CSP, no-cache and no validator, without a session)
    @Test
    void root_servesTheConsole() throws IOException {
        start();

        HttpResponse<String> r = api.get("/");

        assertConsoleHeaders(r, "/");
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(r.body()).contains("<title>").contains("/console.js").contains("aria-live=\"polite\"");
    }

    // Covers: 24.3 (/intents/{id} serves the same page, which reads the id from the path, as after a reauth)
    @Test
    void intentPath_servesTheSamePage() throws IOException {
        start();

        HttpResponse<String> r = api.get("/intents/01234567-89ab-cdef-0123-456789abcdef");

        assertConsoleHeaders(r, "/intents/{id}");
        assertThat(r.body()).isEqualTo(api.get("/").body());
    }

    // Covers: 24.1 (the two other files, with their types)
    @Test
    void scriptAndStylesheet_areServed_withTheirTypes() throws IOException {
        start();

        HttpResponse<String> js = api.get("/console.js");
        HttpResponse<String> css = api.get("/console.css");

        assertConsoleHeaders(js, "/console.js");
        assertThat(js.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/javascript");
        assertThat(js.body()).contains("textContent");
        assertConsoleHeaders(css, "/console.css");
        assertThat(css.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/css");
    }

    // Covers: 23.5, risk 9 (Spring's resource handling stays off: every other path is a 404 in the JSON error shape, a
    // source file is not served under another name, and a path under /intents with a slash or a bad id is a 404 too)
    @Test
    void everythingElse_isA404_inTheJsonShape() throws IOException {
        start();

        for (String path : new String[] {"/nope", "/index.html", "/static/index.html", "/console.js.map", "/favicon.ico",
                "/console.css/", "/intents", "/intents/", "/intents/a/b", "/intents/..%2e%2e",
                "/application.properties", "/META-INF/MANIFEST.MF", "/api/nope"}) {
            HttpResponse<String> r = api.get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(404);
            Map<String, Object> body = LenientJson.readObject(r.body());
            assertThat(body.keySet()).as(path).containsExactlyInAnyOrder("code", "message", "request_id");
            assertThat(body).as(path).containsEntry("code", "not_found");
        }
    }
}
