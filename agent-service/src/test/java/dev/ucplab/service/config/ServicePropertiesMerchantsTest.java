package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.merchant.MerchantId;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code MERCHANTS_FILE} and the {@code MERCHANT_URL} fallback in the service's settings (DESIGN 19, 25.4). */
class ServicePropertiesMerchantsTest {

    @TempDir
    Path dir;

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", "local");
        env.put("APPROVAL_SECRET", "s3cr3t-value-0123456789abcdefghijklmnop");
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("x.db"));
        return env;
    }

    // Covers: 19, 25.4 (without MERCHANTS_FILE the one MERCHANT_URL merchant is the whole registry, as in M6: merchant-a,
    // active, reached at MERCHANT_URL, its lab at MERCHANT_LAB_URL)
    @Test
    void withoutAFile_theOneMerchantUrlMerchant_isTheWholeRegistry() {
        Map<String, String> env = env();
        env.put("MERCHANT_URL", "http://127.0.0.1:9201");
        env.put("MERCHANT_LAB_URL", "http://127.0.0.1:9301");

        ServiceProperties p = ServiceProperties.from(Environment.of(env));

        assertThat(p.merchants()).hasSize(1);
        MerchantSettings m = p.merchants().getFirst();
        assertThat(m.id()).isEqualTo(ServiceProperties.MERCHANT);
        assertThat(m.url()).isEqualTo(URI.create("http://127.0.0.1:9201"));
        assertThat(m.labUrl()).contains(URI.create("http://127.0.0.1:9301"));
        assertThat(p.merchant()).isEqualTo(m.entry(m.fileStatus()));
        assertThat(p.merchantLabUrl()).isEqualTo(URI.create("http://127.0.0.1:9301"));
    }

    // Covers: 19 (with MERCHANTS_FILE the file lists the merchants in order, and the first is the one the single-merchant
    // settings mean; MERCHANT_URL is not read for the registry)
    @Test
    void withAFile_theFileListsTheMerchants_andTheFirstIsTheDefault() throws IOException {
        Path file = Files.writeString(dir.resolve("merchants.yaml"), """
                merchants:
                  - id: shop-one
                    name: One
                    url: http://127.0.0.1:9101
                    lab_url: http://127.0.0.1:9001
                  - id: shop-two
                    name: Two
                    url: http://127.0.0.1:9102
                """);
        Map<String, String> env = env();
        env.put("MERCHANTS_FILE", file.toString());
        env.put("MERCHANT_URL", "http://127.0.0.1:9999");

        ServiceProperties p = ServiceProperties.from(Environment.of(env));

        assertThat(p.merchants()).extracting(MerchantSettings::id)
                .containsExactly(new MerchantId("shop-one"), new MerchantId("shop-two"));
        assertThat(p.merchant().id()).isEqualTo(new MerchantId("shop-one"));
        assertThat(p.merchant().baseUrl()).isEqualTo(URI.create("http://127.0.0.1:9101"));
        assertThat(p.merchantLabUrl()).isEqualTo(URI.create("http://127.0.0.1:9001"));
    }

    // Covers: 19, I8 (a bad merchants file refuses startup with MerchantsFile's fixed message, not the file's text)
    @Test
    void aBadFile_refusesStartup_withAFixedMessage() throws IOException {
        Path file = Files.writeString(dir.resolve("merchants.yaml"), "merchants:\n  - id: a\n    canary-key: 1\n");
        Map<String, String> env = env();
        env.put("MERCHANTS_FILE", file.toString());

        assertThatThrownBy(() -> ServiceProperties.from(Environment.of(env))).hasMessage(MerchantsFile.UNKNOWN_KEY);
    }
}
