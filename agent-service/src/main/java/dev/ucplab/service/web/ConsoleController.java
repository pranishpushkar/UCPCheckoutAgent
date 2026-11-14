package dev.ucplab.service.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lab console (24.1, m6-plan section 7): {@code index.html} at {@code /} and at {@code /intents/{id}} (the page
 * reads the id from the path, as after a reauth), and its two files. Spring's resource handling is off
 * ({@code spring.web.resources.add-mappings=false}), so exactly these four paths are served and every other one is the
 * JSON 404. The files are read once at start.
 *
 * <p>The headers: the Content-Security-Policy of section 7 (no inline script or style), {@code nosniff}, and
 * {@code Cache-Control: no-cache}: never {@code no-store} (the files are public and the browser may keep them, revalidating
 * each time) and never a max-age or a validator (section 20 caches nothing stale).
 */
@RestController
public final class ConsoleController {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; "
            + "base-uri 'none'; frame-ancestors 'none'";

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final MediaType JS = new MediaType("text", "javascript", StandardCharsets.UTF_8);
    private static final MediaType CSS = new MediaType("text", "css", StandardCharsets.UTF_8);

    private final byte[] index = load("index.html");
    private final byte[] script = load("console.js");
    private final byte[] stylesheet = load("console.css");

    @GetMapping("/")
    ResponseEntity<byte[]> root() {
        return serve(index, HTML);
    }

    @GetMapping("/intents/{id:[A-Za-z0-9_-]{1,64}}")
    ResponseEntity<byte[]> intent(@PathVariable("id") String id) {
        return serve(index, HTML);
    }

    @GetMapping("/console.js")
    ResponseEntity<byte[]> script() {
        return serve(script, JS);
    }

    @GetMapping("/console.css")
    ResponseEntity<byte[]> stylesheet() {
        return serve(stylesheet, CSS);
    }

    private static ResponseEntity<byte[]> serve(byte[] body, MediaType type) {
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("Content-Security-Policy", CSP).header("X-Content-Type-Options", "nosniff").body(body);
    }

    private static byte[] load(String name) {
        try (InputStream in = new ClassPathResource("static/" + name).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("the console file " + name + " is missing from the jar", e);
        }
    }
}
