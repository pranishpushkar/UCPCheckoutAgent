package dev.ucplab.service.lab;

import dev.ucplab.service.config.ServiceProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The fault proxy's admin API (12): {@code POST /_proxy/rules} replaces the rule set and {@code DELETE /_proxy/rules}
 * clears it. The proxy is where the agent reaches the merchant ({@code MERCHANT_URL}), so its admin routes are on that
 * address. Local profile only (23.3). Nothing here sends a credential or reads the {@code bodyHmac} key, which no route
 * exposes.
 */
@Component
@Profile("local")
public class ProxyAdmin {

    /** The proxy refused the rule set (400); the message is ours and never echoes the proxy's detail or the input. */
    public static final class RulesRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RulesRejected() {
            super("the proxy refused the rules", null, false, false);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI rules;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public ProxyAdmin(ServiceProperties properties) {
        this.rules = properties.merchant().baseUrl().resolve("/_proxy/rules");
    }

    /** Replaces the proxy's rule set with {@code ruleSetJson} (the section 12 format). */
    public void replace(String ruleSetJson) {
        Objects.requireNonNull(ruleSetJson, "ruleSetJson");
        int status = send(HttpRequest.newBuilder(rules).timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ruleSetJson, StandardCharsets.UTF_8)).build());
        if (status == 400) {
            throw new RulesRejected();
        }
        expect(status);
    }

    /** Removes every rule. */
    public void clear() {
        expect(send(HttpRequest.newBuilder(rules).timeout(TIMEOUT).DELETE().build()));
    }

    private int send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException("the proxy is not reachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static void expect(int status) {
        if (status != 204 && status != 200) {
            throw new IllegalStateException("the proxy answered " + status);
        }
    }
}
