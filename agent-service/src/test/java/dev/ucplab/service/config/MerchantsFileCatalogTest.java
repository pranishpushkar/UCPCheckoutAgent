package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A catalog source in {@code merchants.yaml} (19 "Catalog sources", 26.7, I12). */
class MerchantsFileCatalogTest {

    static final String CATALOG_URL = "https://catalog.shopify.com/api/ucp/mcp";

    @TempDir
    Path dir;

    static String catalog(String url, String extra) {
        return "merchants:\n  - id: shopify-catalog\n    name: Shopify catalog\n    url: " + url + "\n"
                + "    transport: catalog\n    completion: link\n" + extra;
    }

    private List<MerchantSettings> load(String yaml) throws IOException {
        return MerchantsFile.load(Files.writeString(dir.resolve("merchants.yaml"), yaml), AgentProfile.LOCAL);
    }

    // Covers: 19, 26.7, 26.4 (a catalog source is read: transport catalog, completion link, not owned, no shop_domain,
    // credential_ref optional; its fingerprint is 19's)
    @Test
    void load_aCatalogSource() throws IOException {
        MerchantSettings m = load(catalog(CATALOG_URL, "    credential_ref: env:SHOPIFY_CATALOG\n")).getFirst();

        assertThat(m.transport()).isEqualTo(Transport.CATALOG);
        assertThat(m.completion()).isEqualTo(Completion.LINK);
        assertThat(m.owned()).isFalse();
        assertThat(m.shopDomain()).isEmpty();
        assertThat(m.credentialRef()).contains("env:SHOPIFY_CATALOG");
        assertThat(m.entry(MerchantStatus.ACTIVE).fingerprint())
                .isEqualTo("{\"completion\":\"link\",\"owned\":false,\"transport\":\"catalog\"}");
        assertThat(load(catalog(CATALOG_URL, "")).getFirst().credentialRef()).isEmpty();
        assertThat(load(catalog("http://127.0.0.1:8191/api/ucp/mcp", "")).getFirst().transport())
                .isEqualTo(Transport.CATALOG);
    }

    // Covers: 19, I12 (catalog goes with link only, and link with catalog only)
    @Test
    void load_catalogAndLink_goTogether() {
        String handoff = "merchants:\n  - id: c\n    name: C\n    url: " + CATALOG_URL
                + "\n    transport: catalog\n    completion: handoff\n";
        String rest = "merchants:\n  - id: c\n    name: C\n    url: http://127.0.0.1:9001\n    completion: link\n";
        String mcp = "merchants:\n  - id: c\n    name: C\n    url: https://example-dev.myshopify.com\n"
                + "    transport: mcp\n    completion: link\n    owned: true\n"
                + "    shop_domain: example-dev.myshopify.com\n    credential_ref: env:SHOPIFY_CLIENT\n";
        for (String yaml : List.of(handoff, rest, mcp)) {
            assertThatThrownBy(() -> load(yaml)).as(yaml).hasMessage(MerchantsFile.CATALOG_LINK_PAIR);
        }
    }

    // Covers: 19, I12, 26.5 (a catalog entry has none of a store's keys: owned, shop_domain, requires_buyer_ip,
    // payment_handler, endpoint_hosts, ucp_versions)
    @Test
    void load_aCatalogEntry_withAStoresKey_isRefused() {
        for (String extra : List.of("    owned: true\n", "    owned: false\n",
                "    shop_domain: example-dev.myshopify.com\n", "    requires_buyer_ip: true\n",
                "    payment_handler:\n      id: shopify.card\n      type: card\n      credential_type: token\n"
                        + "      token_ref: env:PAY\n",
                "    endpoint_hosts: [catalog.shopify.com]\n", "    ucp_versions: [\"2026-04-08\"]\n")) {
            assertThatThrownBy(() -> load(catalog(CATALOG_URL, extra))).as(extra)
                    .hasMessage(MerchantsFile.CATALOG_STORE_KEY);
        }
    }

    // Covers: 19, 26.7 (off loopback, a catalog's url is Shopify's Global Catalog exactly)
    @Test
    void load_aCatalogUrl_otherThanShopifys_isRefused() {
        for (String url : List.of("https://catalog.shopify.com/api/ucp/mcp/", "https://catalog.shopify.com/other",
                "http://catalog.shopify.com/api/ucp/mcp", "https://catalog.example/api/ucp/mcp")) {
            assertThatThrownBy(() -> load(catalog(url, ""))).as(url).hasMessage(MerchantsFile.CATALOG_URL_NOT_SHOPIFYS);
        }
    }

    private Map<String, String> env(String yaml) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", "local");
        env.put("APPROVAL_SECRET", "s3cr3t-value-0123456789abcdefghijklmnop");
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("x.db"));
        env.put("MERCHANTS_FILE", Files.writeString(dir.resolve("m.yaml"), yaml).toString());
        env.put("UCP_AGENT_PROFILE", ServicePropertiesH5Test.OUR_PROFILE);
        env.put("UCP_AGENT_VERSION", "2026-04-08");
        return env;
    }

    // Covers: 19, 26.7 (a catalog source starts with a real agent profile at 2026-04-08; even on loopback, the
    // default profile and any other version are refused)
    @Test
    void startup_aCatalogSource_needsTheAgentProfileAt20260408() throws IOException {
        String loopback = catalog("http://127.0.0.1:8191/api/ucp/mcp", "");
        assertThat(ServiceProperties.from(Environment.of(env(loopback))).merchants().getFirst().transport())
                .isEqualTo(Transport.CATALOG);

        Map<String, String> unset = env(loopback);
        unset.remove("UCP_AGENT_PROFILE");
        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(unset)))
                .hasMessage(ServiceProperties.AGENT_PROFILE_DEFAULT);

        Map<String, String> otherVersion = env(loopback);
        otherVersion.put("UCP_AGENT_VERSION", "2026-08-25");
        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(otherVersion)))
                .hasMessage(ServiceProperties.CATALOG_VERSION);
    }
}
