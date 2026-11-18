package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The registry entry's H5 fields and the loopback default of {@code owned} (Appendix B, 19, I12; H5 C9). */
class MerchantEntryTest {

    private static final MerchantId A = new MerchantId("a");

    // Covers: Appendix B, 19, I12 (the short form is a lab entry: rest, agent, no shop_domain, owned only on loopback)
    @Test
    void shortForm_defaults_ownedOnlyOnLoopback() {
        MerchantEntry lab = new MerchantEntry(A, URI.create("http://127.0.0.1:9101"), "A", MerchantStatus.ACTIVE);
        assertThat(lab.transport()).isEqualTo(Transport.REST);
        assertThat(lab.completion()).isEqualTo(Completion.AGENT);
        assertThat(lab.shopDomain()).isEmpty();
        assertThat(lab.owned()).isTrue();

        assertThat(new MerchantEntry(A, URI.create("https://shop.example"), "A", MerchantStatus.ACTIVE).owned())
                .isFalse();
    }

    // Covers: I12, 19 (loopback is the parsed host compared exactly: localhost, a 127.x.x.x literal, [::1]; a look-alike
    // name, a decimal or other odd form is not loopback)
    @Test
    void loopback_isAnExactHost() {
        for (String yes : List.of("http://localhost", "http://LOCALHOST:9001", "http://127.0.0.1:9101",
                "http://127.255.0.7", "http://[::1]:9001", "https://localhost/x")) {
            assertThat(MerchantEntry.isLoopback(URI.create(yes))).as(yes).isTrue();
        }
        for (String no : List.of("http://127.0.0.1.evil.example", "http://localhost.evil.example",
                "http://evil.example/127.0.0.1", "http://2130706433", "http://0x7f.0.0.1", "http://0177.0.0.1",
                "http://127.1", "http://127.0.0.01", "http://127.0.0.256", "http://[::ffff:127.0.0.1]",
                "http://[0:0:0:0:0:0:0:1]", "http://128.0.0.1", "http://10.0.0.1", "http://localhost@evil.example",
                "file:///etc/hosts", "http://shop.example")) {
            assertThat(MerchantEntry.isLoopback(URI.create(no))).as(no).isFalse();
        }
    }

    // Covers: Appendix B, 19 (the full form keeps what it is given; an MCP entry needs a shop_domain)
    @Test
    void fullForm_keepsItsFields_andMcpNeedsAShopDomain() {
        MerchantEntry shop = new MerchantEntry(A, URI.create("https://example-dev.myshopify.com"), "Dev store",
                MerchantStatus.ACTIVE, Transport.MCP, Completion.HANDOFF, true,
                Optional.of("example-dev.myshopify.com"));
        assertThat(shop.transport()).isEqualTo(Transport.MCP);
        assertThat(shop.completion()).isEqualTo(Completion.HANDOFF);
        assertThat(shop.owned()).isTrue();
        assertThat(shop.shopDomain()).contains("example-dev.myshopify.com");

        assertThatThrownBy(() -> new MerchantEntry(A, URI.create("https://x.example"), "X", MerchantStatus.ACTIVE,
                Transport.MCP, Completion.HANDOFF, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("an MCP merchant needs a shop_domain");
    }

    // Covers: 10, 19, I12 (the fingerprint is CanonicalJson of completion, owned, shop_domain and transport, lower-case;
    // a lab entry's is the V8 default; shop_domain is left out when empty)
    @Test
    void fingerprint_isTheFourI12Fields_asCanonicalJson() {
        MerchantEntry lab = new MerchantEntry(A, URI.create("http://127.0.0.1:8081"), "Lab", MerchantStatus.ACTIVE);
        MerchantEntry shop = new MerchantEntry(A, URI.create("https://s.myshopify.com"), "S", MerchantStatus.ACTIVE,
                Transport.MCP, Completion.HANDOFF, true, Optional.of("s.myshopify.com"));

        assertThat(lab.fingerprint()).isEqualTo(MerchantEntry.LAB_FINGERPRINT)
                .isEqualTo("{\"completion\":\"agent\",\"owned\":true,\"transport\":\"rest\"}");
        assertThat(shop.fingerprint()).isEqualTo(
                "{\"completion\":\"handoff\",\"owned\":true,\"shop_domain\":\"s.myshopify.com\",\"transport\":\"mcp\"}");
    }

    // Covers: 10, 19, I12 (each of the four fields changes the fingerprint; id, URL, name and status do not)
    @Test
    void fingerprint_changesWithEachI12Field_only() {
        MerchantEntry base = new MerchantEntry(A, URI.create("https://s.myshopify.com"), "S", MerchantStatus.ACTIVE,
                Transport.MCP, Completion.HANDOFF, true, Optional.of("s.myshopify.com"));
        String f = base.fingerprint();

        assertThat(List.of(
                new MerchantEntry(A, base.baseUrl(), "S", MerchantStatus.ACTIVE, Transport.MCP, Completion.AGENT, true,
                        Optional.of("s.myshopify.com")),
                new MerchantEntry(A, base.baseUrl(), "S", MerchantStatus.ACTIVE, Transport.MCP, Completion.HANDOFF, false,
                        Optional.of("s.myshopify.com")),
                new MerchantEntry(A, base.baseUrl(), "S", MerchantStatus.ACTIVE, Transport.MCP, Completion.HANDOFF, true,
                        Optional.of("t.myshopify.com")),
                new MerchantEntry(A, base.baseUrl(), "S", MerchantStatus.ACTIVE, Transport.REST, Completion.HANDOFF, true,
                        Optional.of("s.myshopify.com")),
                new MerchantEntry(A, base.baseUrl(), "S", MerchantStatus.ACTIVE, Transport.REST, Completion.HANDOFF, true,
                        Optional.empty())))
                .extracting(MerchantEntry::fingerprint).doesNotContain(f).doesNotHaveDuplicates();
        assertThat(new MerchantEntry(new MerchantId("b"), URI.create("https://other.example"), "T",
                MerchantStatus.SUSPENDED, Transport.MCP, Completion.HANDOFF, true, Optional.of("s.myshopify.com"))
                .fingerprint()).isEqualTo(f);
    }
}
