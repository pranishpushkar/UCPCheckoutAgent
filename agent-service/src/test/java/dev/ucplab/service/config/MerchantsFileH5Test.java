package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.ucp.registry.MerchantSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real-merchant keys of {@code merchants.yaml} (19 "Real merchants (H5)", 26.4, 8.4, I12; H5 C9). */
class MerchantsFileH5Test {

    private static final String CANARY = "canary-secret-value";

    @TempDir
    Path dir;

    private static String store(String extra) {
        return "merchants:\n  - id: dev-store\n    name: Dev store\n    url: https://example-dev.myshopify.com\n" + extra;
    }

    private static final String MCP = """
                transport: mcp
                completion: handoff
                owned: true
                shop_domain: example-dev.myshopify.com
                credential_ref: env:SHOPIFY_CLIENT
            """;

    private List<MerchantSettings> load(String yaml, AgentProfile profile) throws IOException {
        return MerchantsFile.load(Files.writeString(dir.resolve("merchants.yaml"), yaml), profile);
    }

    // Covers: 19, 26.4, 8.4, Appendix B (every H5 key is read, and the entry carries transport, completion, owned and
    // shop_domain)
    @Test
    void load_theH5Keys_areRead() throws IOException {
        MerchantSettings m = load(store(MCP + "    requires_buyer_ip: true\n"), AgentProfile.LOCAL).getFirst();

        assertThat(m.transport()).isEqualTo(Transport.MCP);
        assertThat(m.completion()).isEqualTo(Completion.HANDOFF);
        assertThat(m.owned()).isTrue();
        assertThat(m.shopDomain()).contains("example-dev.myshopify.com");
        assertThat(m.credentialRef()).contains("env:SHOPIFY_CLIENT");
        assertThat(m.requiresBuyerIp()).isTrue();
        MerchantEntry e = m.entry(MerchantStatus.ACTIVE);
        assertThat(e.transport()).isEqualTo(Transport.MCP);
        assertThat(e.completion()).isEqualTo(Completion.HANDOFF);
        assertThat(e.owned()).isTrue();
        assertThat(e.shopDomain()).contains("example-dev.myshopify.com");
    }

    // Covers: 19, I12 (the defaults: rest, agent, no credentials, no buyer IP, owned on a loopback host; a lab merchant
    // reads exactly as before)
    @Test
    void load_defaults_keepTheLabMerchantsAsTheyWere() throws IOException {
        MerchantSettings lab = load("merchants:\n  - id: a\n    name: A\n    url: http://127.0.0.1:9101\n",
                AgentProfile.PROD).getFirst();

        assertThat(lab.transport()).isEqualTo(Transport.REST);
        assertThat(lab.completion()).isEqualTo(Completion.AGENT);
        assertThat(lab.owned()).isTrue();
        assertThat(lab.shopDomain()).isEmpty();
        assertThat(lab.credentialRef()).isEmpty();
        assertThat(lab.requiresBuyerIp()).isFalse();
        assertThat(load(store("    owned: true\n"), AgentProfile.PROD).getFirst().owned()).isTrue();
        assertThat(load(store("    owned: true\n    completion: handoff\n"), AgentProfile.PROD).getFirst().completion())
                .isEqualTo(Completion.HANDOFF);
    }

    // Covers: 19, I12, 26.4, 8.4, I8 (each startup refusal has its own fixed reason, which never echoes the file)
    @Test
    void load_refusals_eachHaveAFixedReason() {
        Map<String, Object[]> table = new LinkedHashMap<>();
        table.put("mcp without credential_ref", new Object[] {store("""
                    transport: mcp
                    owned: true
                    shop_domain: example-dev.myshopify.com
                """), MerchantsFile.MCP_INCOMPLETE, AgentProfile.LOCAL});
        table.put("mcp without shop_domain", new Object[] {store("""
                    transport: mcp
                    owned: true
                    credential_ref: env:SHOPIFY_CLIENT
                """), MerchantsFile.MCP_INCOMPLETE, AgentProfile.LOCAL});
        table.put("mcp with completion: agent", new Object[] {store(MCP.replace("completion: handoff",
                "completion: agent")), MerchantsFile.MCP_AGENT_COMPLETION, AgentProfile.LOCAL});
        table.put("mcp with completion left to its default, agent", new Object[] {store(MCP.replace(
                "    completion: handoff\n", "")), MerchantsFile.MCP_AGENT_COMPLETION, AgentProfile.LOCAL});
        table.put("off loopback without owned", new Object[] {store(""), MerchantsFile.NOT_OWNED, AgentProfile.LOCAL});
        table.put("off loopback with owned: false", new Object[] {store("    owned: false\n"), MerchantsFile.NOT_OWNED,
                AgentProfile.LOCAL});
        table.put("a look-alike loopback name", new Object[] {"merchants:\n  - id: a\n    name: A\n"
                + "    url: http://127.0.0.1.evil.example:9101\n", MerchantsFile.NOT_OWNED, AgentProfile.LOCAL});
        table.put("requires_buyer_ip in demo", new Object[] {store(MCP + "    requires_buyer_ip: true\n"),
                MerchantsFile.BUYER_IP_NOT_LOCAL, AgentProfile.DEMO});
        table.put("requires_buyer_ip in prod", new Object[] {store(MCP + "    requires_buyer_ip: true\n"),
                MerchantsFile.BUYER_IP_NOT_LOCAL, AgentProfile.PROD});
        table.put("a credential in place of a credential_ref", new Object[] {store(MCP.replace("env:SHOPIFY_CLIENT",
                "shpss_" + CANARY)), MerchantsFile.BAD_CREDENTIAL_REF, AgentProfile.LOCAL});
        table.put("a credential_ref from a file", new Object[] {store(MCP.replace("env:SHOPIFY_CLIENT",
                "file:/tmp/" + CANARY)), MerchantsFile.BAD_CREDENTIAL_REF, AgentProfile.LOCAL});
        table.put("a lowercase credential_ref", new Object[] {store(MCP.replace("env:SHOPIFY_CLIENT", "env:lower")),
                MerchantsFile.BAD_CREDENTIAL_REF, AgentProfile.LOCAL});
        table.put("a transport of grpc", new Object[] {store("    owned: true\n    transport: grpc-" + CANARY + "\n"),
                MerchantsFile.BAD_TRANSPORT, AgentProfile.LOCAL});
        table.put("a completion of auto", new Object[] {store("    owned: true\n    completion: auto-" + CANARY + "\n"),
                MerchantsFile.BAD_COMPLETION, AgentProfile.LOCAL});
        table.put("owned as a string", new Object[] {store("    owned: \"yes-" + CANARY + "\"\n"),
                MerchantsFile.WRONG_TYPE, AgentProfile.LOCAL});
        table.put("requires_buyer_ip as a string", new Object[] {store(MCP + "    requires_buyer_ip: \"" + CANARY
                + "\"\n"), MerchantsFile.WRONG_TYPE, AgentProfile.LOCAL});
        table.put("a shop_domain with a port", new Object[] {store(MCP.replace("example-dev.myshopify.com",
                "example-dev.myshopify.com:443")), MerchantsFile.BAD_SHOP_DOMAIN, AgentProfile.LOCAL});
        table.put("a shop_domain with a path", new Object[] {store(MCP.replace("example-dev.myshopify.com",
                "example-dev.myshopify.com/" + CANARY)), MerchantsFile.BAD_SHOP_DOMAIN, AgentProfile.LOCAL});
        table.put("a shop_domain as a URL", new Object[] {store(MCP.replace("example-dev.myshopify.com",
                "https://example-dev.myshopify.com")), MerchantsFile.BAD_SHOP_DOMAIN, AgentProfile.LOCAL});
        table.put("a buyer IP in the file", new Object[] {store(MCP + "    buyer_ip: 81.2.69.160\n"),
                MerchantsFile.UNKNOWN_KEY, AgentProfile.LOCAL});

        table.forEach((name, row) -> assertThatThrownBy(() -> load((String) row[0], (AgentProfile) row[2])).as(name)
                .isInstanceOf(IllegalStateException.class).hasMessage((String) row[1]).message()
                .doesNotContain(CANARY).doesNotContain("81.2.69.160"));
    }

    // Covers: 19 (a loopback merchant may say owned explicitly; owned: false keeps it unowned, and the orchestrator
    // refuses its writes, I12)
    @Test
    void load_aLoopbackMerchant_mayStateOwned() throws IOException {
        String lab = "merchants:\n  - id: a\n    name: A\n    url: http://127.0.0.1:9101\n";
        assertThat(load(lab + "    owned: true\n", AgentProfile.LOCAL).getFirst().owned()).isTrue();
        assertThat(load(lab + "    owned: false\n", AgentProfile.LOCAL).getFirst().owned()).isFalse();
        assertThat(load(lab + "    requires_buyer_ip: false\n", AgentProfile.PROD).getFirst().requiresBuyerIp())
                .isFalse();
    }
}
