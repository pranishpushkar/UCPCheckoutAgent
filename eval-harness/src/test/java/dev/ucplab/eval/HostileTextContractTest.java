package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.LenientJson;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.merchant.LabMerchant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Risk 6 of the M5 plan: the proxy's literal find must match the real merchant's JSON formatting. Run against the lab
 * merchant itself (merchant/src unchanged), directly and through a fault proxy with {@link HostileText#rule()}.
 */
class HostileTextContractTest {

    private static LabMerchant merchant;
    private static FaultProxy proxy;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll
    static void start() throws Exception {
        merchant = LabMerchant.start();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        proxy = FaultProxy.start(merchant.baseUrl(), 0, key);
    }

    @AfterAll
    static void stop() {
        proxy.close();
        merchant.close();
    }

    private static String post(URI base, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Request-Id", "rq-contract")
                .header("UCP-Agent", AgentStack.UCP_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    private static int occurrences(String body, String find) {
        int n = 0;
        for (int i = body.indexOf(find); i >= 0; i = body.indexOf(find, i + 1)) {
            n++;
        }
        return n;
    }

    // Covers: S10, risk 6 (the find string occurs exactly once in the real POST /catalog/product body for item_456)
    @Test
    void find_occursExactlyOnceInTheRealProductBody() throws Exception {
        String body = post(merchant.baseUrl(), "/ucp/v1/catalog/product", "{\"id\":\"" + HostileText.ITEM + "\"}");

        assertThat(occurrences(body, HostileText.FIND)).isEqualTo(1);
    }

    // Covers: S10, §12 forward_then_rewrite_response, 9.3 L6 (through the proxy the product description, and only it,
    // is the hostile text; the body is still JSON; title, price and the variant are untouched; the log says replaced)
    @Test
    @SuppressWarnings("unchecked")
    void rule_plantsTheHostileDescriptionOnlyInTheProductDescription() throws Exception {
        proxy.install(new RuleSet(10, List.of(HostileText.rule())));
        long mark = proxy.log(0).stream().mapToLong(LogEntry::seq).max().orElse(0);
        try {
            String direct = post(merchant.baseUrl(), "/ucp/v1/catalog/product", "{\"id\":\"item_456\"}");
            String body = post(proxy.baseUrl(), "/ucp/v1/catalog/product", "{\"id\":\"item_456\"}");

            Map<String, Object> product = (Map<String, Object>) LenientJson.readObject(body).get("product");
            Map<String, Object> original = (Map<String, Object>) LenientJson.readObject(direct).get("product");
            assertThat(product.get("description")).isEqualTo(Map.of("plain", HostileText.TEXT));
            assertThat(product.get("title")).isEqualTo("Blue Mug");
            assertThat(product.get("price_range")).isEqualTo(original.get("price_range"));
            assertThat(product.get("variants")).isEqualTo(original.get("variants"));
            assertThat(body.replace(HostileText.REPLACE, HostileText.FIND)).isEqualTo(direct);
            List<LogEntry> log = proxy.log(mark);
            assertThat(log).singleElement().satisfies(e -> {
                assertThat(e.forwards()).isEqualTo(1);
                assertThat(e.rewrite()).contains("replaced");
            });
        } finally {
            proxy.clearRules();
        }
    }
}
