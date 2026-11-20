package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.json.LenientJson;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The Global Catalog fixtures (real catalog stage 0, owner-approved) hold no real store, seller or text (I8, 26.5): every
 * file in the folder is scanned, its README included. Their sellers, titles and texts are checked to be ours by shape,
 * so this test names no real store either.
 */
class ShopifyCatalogFixturesTest {

    private static final Path FOLDER = Path.of("src/test/resources/shopify-catalog");
    private static final Set<String> SHOPIFY_HOSTS = Set.of("checkout.shopify.com", "shopify.dev", "ucp.dev");
    private static final Pattern HOST = Pattern.compile("https?://([A-Za-z0-9.-]+)");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern TOKEN_PREFIX = Pattern.compile("shp[a-z]{2}_");
    private static final Pattern BASE64_JSON = Pattern.compile("eyJ[A-Za-z0-9+/=_-]{8,}");

    private static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(FOLDER)) {
            return s.sorted().toList();
        }
    }

    /** Every problem in {@code text}: a host that is not a fake or Shopify's own, a store host, an IP, a token, a cursor. */
    static List<String> violations(String text) {
        List<String> found = new ArrayList<>();
        HOST.matcher(text).results().map(m -> m.group(1).toLowerCase(java.util.Locale.ROOT))
                .filter(h -> !h.endsWith(".example") && !SHOPIFY_HOSTS.contains(h))
                .forEach(h -> found.add("a host that is neither a fake nor Shopify's own: " + h));
        if (text.contains(".myshopify.com")) {
            found.add("a myshopify.com store host");
        }
        if (text.contains("cdn.shopify.com")) {
            found.add("a media URL");
        }
        IPV4.matcher(text).results().forEach(m -> found.add("an IPv4 literal: " + m.group()));
        TOKEN_PREFIX.matcher(text).results().forEach(m -> found.add("a Shopify access token prefix"));
        BASE64_JSON.matcher(text).results().forEach(m -> found.add("a base64 JSON value (a real cursor)"));
        return found;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> products(Map<String, Object> answer) {
        Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) answer.get("result"))
                .get("structuredContent");
        return content.containsKey("product") ? List.of((Map<String, Object>) content.get("product"))
                : (List<Map<String, Object>>) content.get("products");
    }

    // Covers: I8, 26.7 (the folder holds exactly the three approved answers and the README)
    @Test
    void folder_holdsExactlyTheApprovedAnswers() throws IOException {
        assertThat(files()).map(p -> p.getFileName().toString()).containsExactly(
                "README.md", "global_get_product_us.json", "global_lookup.json", "global_search_us.json");
    }

    // Covers: I8, I12, 26.5, 26.7 (no file holds a real host, a store host, a media URL, an IP, a token prefix or a real
    // cursor)
    @Test
    void noFile_holdsAForbiddenValue() throws IOException {
        for (Path file : files()) {
            assertThat(violations(Files.readString(file))).as(file.getFileName().toString()).isEmpty();
        }
    }

    // Covers: I8, 26.5, 26.7 (every seller, title, text and id is one of ours, by shape; every buy link's tracking value
    // is a fake; no media; and the C3 relation stands: the seller's domain is not its URL's host)
    @Test
    @SuppressWarnings("unchecked")
    void everySellerTitleTextAndId_isOurs() throws IOException {
        int offers = 0;
        for (Path file : files()) {
            if (!file.toString().endsWith(".json")) {
                continue;
            }
            String name = file.getFileName().toString();
            assertThat(Files.readString(file)).as(name).doesNotContain("\"media\"");
            for (Map<String, Object> p : products(LenientJson.readObject(Files.readString(file)))) {
                assertThat((String) p.get("title")).as(name).matches("Example Snowboard \\d+");
                assertThat((String) p.get("id")).as(name).matches("gid://shopify/p/FAKEUPID\\d{6}");
                assertThat((String) ((Map<String, Object>) p.get("description")).get("plain")).as(name)
                        .matches("<p>Our own test text for board \\d+\\.</p>");
                for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) {
                    offers++;
                    Map<String, Object> seller = (Map<String, Object>) v.get("seller");
                    assertThat((String) seller.get("name")).as(name).matches("Example Shop [A-Z]");
                    assertThat((String) seller.get("domain")).as(name).matches("shop-[a-z]\\.myshopify\\.example");
                    assertThat((String) v.get("id")).as(name).matches("gid://shopify/ProductVariant/1\\d{6}");
                    assertThat((String) v.get("title")).as(name).isEqualTo(p.get("title"));
                    URI link = URI.create((String) v.get("checkout_url"));
                    URI site = URI.create((String) seller.get("url"));
                    assertThat(link.getHost()).as(name).isEqualTo(site.getHost())
                            .isNotEqualTo(seller.get("domain"));
                    assertThat(link.getPath()).as(name).matches("/buy/\\d+:1");
                    for (String pair : link.getRawQuery().split("&")) {
                        assertThat(pair.substring(pair.indexOf('=') + 1)).as(name + " " + pair)
                                .matches("FAKE\\w+|fake|2026-04-08");
                    }
                }
            }
        }
        assertThat(offers).as("offers checked").isEqualTo(6);
    }

    // Covers: I8 (the scan is not vacuous: it finds each kind, and passes the fixtures' own fakes and Shopify's hosts)
    @Test
    void scan_findsEachKind_andAllowsTheFakes() {
        assertThat(violations("https://www.real-store.com/buy/1:1")).hasSize(1);
        assertThat(violations("https://some-store.myshopify.com/x")).hasSize(2);
        assertThat(violations("https://cdn.shopify.com/s/files/x.jpg")).hasSize(2);
        assertThat(violations("Shopify-Buyer-IP: 203.0.113.7")).hasSize(1);
        assertThat(violations("shpat_0123")).hasSize(1);
        assertThat(violations("\"cursor\": \"eyJvZmZzZXQiOjEwfQ\"")).hasSize(1);
        assertThat(violations("https://www.shop-a.example/buy/1000001:1?_gsid=FAKEGSID0001 shop-a.myshopify.example "
                + "https://checkout.shopify.com/... https://ucp.dev/2026-04-08/x https://shopify.dev/docs")).isEmpty();
    }
}
