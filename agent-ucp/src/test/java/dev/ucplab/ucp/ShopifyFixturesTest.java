package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Shopify fixtures (H5 F) hold nothing that names the real store or could be used against it (I8): every file in
 * the folder is scanned, its README included, and the folder holds exactly the stripped answers.
 */
class ShopifyFixturesTest {

    private static final Path FOLDER = Path.of("src/test/resources/shopify");
    private static final String PLACEHOLDER_HOST = "example-dev.myshopify.com";

    /** Each forbidden kind, by name, for the report. */
    static final Map<String, Pattern> FORBIDDEN = Map.of(
            "a checkout key other than the masked one", Pattern.compile("[?&]key=(?!<redacted>)"),
            "a cart permalink path", Pattern.compile("/cart/c/"),
            "the real store's handle", Pattern.compile("ucplab-dev"),
            "an IPv4 literal", Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])"),
            "an IPv6 literal", Pattern.compile(
                    "(?i)(?<![0-9a-z:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-z:])|[0-9a-f]{0,4}::[0-9a-f]{0,4}"),
            "a Shopify access token prefix", Pattern.compile("shp[a-z]{2}_"));
    /** Any myshopify.com host; only {@link #PLACEHOLDER_HOST} is allowed. */
    private static final Pattern STORE_HOST = Pattern.compile("(?i)[a-z0-9.-]+\\.myshopify\\.com");

    private static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(FOLDER)) {
            return s.sorted().toList();
        }
    }

    /** Every forbidden match in {@code text}, as "kind: match". */
    static List<String> violations(String text) {
        Stream<String> forbidden = FORBIDDEN.entrySet().stream().flatMap(e -> e.getValue().matcher(text).results()
                .map(m -> e.getKey() + ": " + m.group()));
        Stream<String> hosts = STORE_HOST.matcher(text).results().map(m -> m.group())
                .filter(h -> !h.equalsIgnoreCase(PLACEHOLDER_HOST)).map(h -> "a store host other than the placeholder: " + h);
        return Stream.concat(forbidden, hosts).toList();
    }

    // Covers: I8, H5 F (the folder holds exactly the five stripped answers and the README; the global catalog, other
    // stores' products and text, was never copied)
    @Test
    void folder_holdsExactlyTheStrippedAnswers() throws IOException {
        assertThat(files()).map(p -> p.getFileName().toString()).containsExactly(
                "README.md", "cancel_checkout.json", "catalog_search_store.error.json", "catalog_search_store.json",
                "create_checkout.json", "store_profile.json");
    }

    // Covers: I8, I12, H5 F (no file holds a real checkout key, a cart permalink, a store host but the placeholder, an IP
    // literal or an access token prefix)
    @Test
    void noFile_holdsAForbiddenValue() throws IOException {
        for (Path file : files()) {
            assertThat(violations(Files.readString(file))).as(file.getFileName().toString()).isEmpty();
        }
    }

    // Covers: I8, H5 F (the scan is not vacuous: it finds each forbidden kind, and passes the fixtures' own fakes)
    @Test
    void scan_findsEachForbiddenKind_andAllowsThePlaceholders() {
        assertThat(violations("gid://shopify/Checkout/x?key=abc123")).hasSize(1);
        assertThat(violations("https://" + PLACEHOLDER_HOST + "/cart/c/abc")).hasSize(1);
        assertThat(violations("ucplab-dev")).hasSize(1);
        assertThat(violations("Shopify-Buyer-IP: 203.0.113.7")).hasSize(1);
        assertThat(violations("ip 2001:db8:0:0:0:0:0:1 or fe80::1")).hasSize(2);
        assertThat(violations("shpat_0123 and shpca_4567")).hasSize(2);
        assertThat(violations("https://Other-Store.myshopify.com/x")).hasSize(1);

        assertThat(violations("gid://shopify/Checkout/fakeCheckoutToken0000001?key=<redacted> https://"
                + PLACEHOLDER_HOST + "/... at 2026-11-16T10:12:34Z, version 2026-04-08, amount 69995")).isEmpty();
    }

    // Covers: H5 F, 8.1 (each answer is JSON our reader takes, and each MCP answer's content text is its
    // structuredContent, so a test may read either)
    @Test
    void everyAnswer_parses_andItsTextCopyMatches() throws IOException {
        for (Path file : files()) {
            if (!file.toString().endsWith(".json")) {
                continue;
            }
            Map<String, Object> answer = LenientJson.readObject(Files.readString(file));
            if (answer.get("result") instanceof Map<?, ?> result && result.containsKey("structuredContent")) {
                List<?> content = (List<?>) result.get("content");
                String text = (String) ((Map<?, ?>) content.get(0)).get("text");
                assertThat(LenientJson.readObject(text)).as(file.getFileName().toString())
                        .isEqualTo(result.get("structuredContent"));
            }
        }
    }
}
