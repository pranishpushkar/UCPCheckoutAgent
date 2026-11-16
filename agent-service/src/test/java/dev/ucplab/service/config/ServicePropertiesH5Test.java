package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.ucp.registry.BuyerIp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the environment must hold for the registry's real-merchant settings (19 "Real merchants (H5)", 26.4, 8.4, I8,
 * I12; H5 C9): the MERCHANT_URL fallback stays on loopback, BUYER_IP comes from the environment only and is a public
 * literal, and every credential_ref names a set variable.
 */
class ServicePropertiesH5Test {

    private static final String IP = "81.2.69.160";
    private static final String CANARY = "canary-client-secret-4b1e";

    @TempDir
    Path dir;

    private Map<String, String> env(String profile) {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", profile);
        env.put("APPROVAL_SECRET", "s3cr3t-value-0123456789abcdefghijklmnop");
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("x.db"));
        return env;
    }

    private Map<String, String> withStore(String profile, boolean buyerIp) throws IOException {
        Path file = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: dev-store
                    name: Dev store
                    url: https://example-dev.myshopify.com
                    transport: mcp
                    completion: handoff
                    owned: true
                    shop_domain: example-dev.myshopify.com
                    credential_ref: env:SHOPIFY_CLIENT
                """ + (buyerIp ? "    requires_buyer_ip: true\n" : ""));
        Map<String, String> env = env(profile);
        env.put("MERCHANTS_FILE", file.toString());
        env.put("SHOPIFY_CLIENT", CANARY);
        return env;
    }

    // Covers: I12, 19 (without MERCHANTS_FILE the MERCHANT_URL merchant cannot say owned, so it must be on loopback;
    // with a file, MERCHANT_URL is not read for the registry)
    @Test
    void merchantUrlFallback_offLoopback_refusesStartup() throws IOException {
        for (String url : java.util.List.of("https://shop.example", "http://127.0.0.1.evil.example:9101",
                "http://localhost.evil.example", "http://2130706433:9101")) {
            Map<String, String> env = env("local");
            env.put("MERCHANT_URL", url);
            assertThatThrownBy(() -> ServiceProperties.from(Environment.of(env))).as(url)
                    .hasMessage(ServiceProperties.MERCHANT_NOT_LOOPBACK);
        }
        Map<String, String> env = withStore("local", false);
        env.put("MERCHANT_URL", "https://shop.example");
        assertThat(ServiceProperties.from(Environment.of(env)).merchants()).hasSize(1);
    }

    // Covers: 8.4, 19, I8 (requires_buyer_ip needs BUYER_IP; BUYER_IP is a public literal, accepted in local only; every
    // refusal is fixed and never holds the value)
    @Test
    void buyerIp_rules() throws IOException {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("required but unset", new String[] {"local", "true", null, ServiceProperties.BUYER_IP_REQUIRED});
        table.put("a name", new String[] {"local", "true", "shop.example", BuyerIp.INVALID});
        table.put("private", new String[] {"local", "true", "192.168.1.7", BuyerIp.NOT_PUBLIC});
        table.put("TEST-NET", new String[] {"local", "true", "203.0.113.5", BuyerIp.NOT_PUBLIC});
        table.put("loopback", new String[] {"local", "true", "127.0.0.1", BuyerIp.NOT_PUBLIC});
        // demo is refused earlier (AGENT_PROFILE=demo is not available in M6); MerchantsFileH5Test covers it for the file.
        // demo is refused earlier (AGENT_PROFILE=demo is not available in M6); MerchantsFileH5Test covers it for the file.
        table.put("set in prod", new String[] {"prod", "false", IP, ServiceProperties.BUYER_IP_NOT_LOCAL});
        for (Map.Entry<String, String[]> row : table.entrySet()) {
            String[] r = row.getValue();
            Map<String, String> env = withStore(r[0], Boolean.parseBoolean(r[1]));
            if (r[2] != null) {
                env.put("BUYER_IP", r[2]);
            }
            assertThatThrownBy(() -> ServiceProperties.from(Environment.of(env))).as(row.getKey())
                    .isInstanceOf(IllegalStateException.class).hasMessage(r[3]).message().doesNotContain(IP)
                    .doesNotContain("192.168.1.7").doesNotContain(CANARY);
        }

        Map<String, String> env = withStore("local", true);
        env.put("BUYER_IP", IP);
        ServiceProperties p = ServiceProperties.from(Environment.of(env));
        assertThat(p.buyerIp()).hasValueSatisfying(ip -> assertThat(ip.value()).isEqualTo(IP));
        assertThat(p.toString()).doesNotContain(IP).doesNotContain(CANARY);
        assertThat(String.valueOf(p.buyerIp())).doesNotContain(IP);
    }

    // Covers: 8.4, 19 (BUYER_IP is optional when no merchant requires it, and is still checked when set)
    @Test
    void buyerIp_withoutAMerchantThatNeedsIt() throws IOException {
        assertThat(ServiceProperties.from(Environment.of(withStore("local", false))).buyerIp()).isEmpty();
        Map<String, String> env = withStore("local", false);
        env.put("BUYER_IP", "10.0.0.1");
        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(env))).hasMessage(BuyerIp.NOT_PUBLIC);
    }

    // Covers: 26.4, 19, I8 (a credential_ref naming an unset variable refuses startup; the message names neither the
    // variable's value nor the file)
    @Test
    void credentialRef_namingAnUnsetVariable_refusesStartup() throws IOException {
        Map<String, String> env = withStore("local", false);
        env.remove("SHOPIFY_CLIENT");
        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(env)))
                .hasMessage(ServiceProperties.CREDENTIAL_UNSET);

        Map<String, String> blank = withStore("local", false);
        blank.put("SHOPIFY_CLIENT", " ");
        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(blank)))
                .hasMessage(ServiceProperties.CREDENTIAL_UNSET);
    }
}
