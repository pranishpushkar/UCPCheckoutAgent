package dev.ucplab.service.config;

import dev.ucplab.ucp.registry.MerchantSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code MERCHANTS_FILE} (DESIGN 19, 26.2, 8.5): every key by name, every refusal a fixed message that never echoes the
 * file (I8, CLAUDE.md). Table-driven: one row per bad key or value.
 */
class MerchantsFileTest {

    private static final String CANARY = "canary-secret-value";

    @TempDir
    Path dir;

    private static String merchant(String id, String extra) {
        return "  - id: " + id + "\n    name: Shop " + id + "\n    url: http://127.0.0.1:9101\n" + extra;
    }

    private List<MerchantSettings> load(String yaml, AgentProfile profile) throws IOException {
        return MerchantsFile.load(Files.writeString(dir.resolve("merchants.yaml"), yaml), profile);
    }

    // Covers: 19, 26.2, 8.5 (every key DESIGN 19 lists is read, with its default when left out)
    @Test
    void load_everyKey_isRead() throws IOException {
        List<MerchantSettings> all = load("merchants:\n" + merchant("merchant-a", """
                    status: suspended
                    lab_url: http://127.0.0.1:9001
                    endpoint_hosts: ["api.shop.example:8443"]
                    ucp_versions: ["2026-08-25", "2026-04-08"]
                    payment_handler:
                      id: mock_payment_handler
                      type: card
                      credential_type: token
                      token_ref: env:SAMPLE_CARD_TOKEN
                    transport: rest
                    completion: agent
                """) + merchant("merchant-b", ""), AgentProfile.LOCAL);

        assertThat(all).hasSize(2);
        MerchantSettings a = all.get(0);
        assertThat(a.id()).isEqualTo(new MerchantId("merchant-a"));
        assertThat(a.name()).isEqualTo("Shop merchant-a");
        assertThat(a.url()).isEqualTo(URI.create("http://127.0.0.1:9101"));
        assertThat(a.fileStatus()).isEqualTo(MerchantStatus.SUSPENDED);
        assertThat(a.labUrl()).contains(URI.create("http://127.0.0.1:9001"));
        assertThat(a.endpointHosts()).containsExactly("api.shop.example:8443");
        assertThat(a.ucpVersions()).containsExactlyInAnyOrder("2026-08-25", "2026-04-08");
        assertThat(a.paymentHandler()).contains(new MerchantSettings.PaymentHandler("mock_payment_handler", "card",
                "token", "env:SAMPLE_CARD_TOKEN"));
        MerchantSettings b = all.get(1);
        assertThat(b.fileStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(b.labUrl()).isEmpty();
        assertThat(b.endpointHosts()).isEmpty();
        assertThat(b.ucpVersions()).containsExactly("2026-08-25");
        assertThat(b.paymentHandler()).isEqualTo(Optional.empty());
    }

    // Covers: 19, I8 (each bad key or value is refused with its own fixed message, which never echoes the file)
    @Test
    void load_badFiles_areRefusedWithFixedMessages() {
        Map<String, String[]> table = new LinkedHashMap<>();
        table.put("not a map", new String[] {"- a\n- b\n", MerchantsFile.NOT_A_MAP});
        table.put("no merchants key", new String[] {"other: 1\n", MerchantsFile.UNKNOWN_KEY});
        table.put("merchants not a list", new String[] {"merchants: " + CANARY + "\n", MerchantsFile.WRONG_TYPE});
        table.put("an empty list", new String[] {"merchants: []\n", MerchantsFile.COUNT});
        table.put("21 merchants", new String[] {
                "merchants:\n" + String.join("", java.util.stream.IntStream.range(0, 21)
                        .mapToObj(i -> merchant("m" + i, "")).toList()), MerchantsFile.COUNT});
        table.put("a merchant that is not a map", new String[] {"merchants:\n  - " + CANARY + "\n", MerchantsFile.WRONG_TYPE});
        table.put("an unknown key", new String[] {"merchants:\n" + merchant("a", "    " + CANARY + ": 1\n"), MerchantsFile.UNKNOWN_KEY});
        table.put("no id", new String[] {"merchants:\n  - name: A\n    url: http://127.0.0.1:1\n", MerchantsFile.MISSING_KEY});
        table.put("no name", new String[] {"merchants:\n  - id: a\n    url: http://127.0.0.1:1\n", MerchantsFile.MISSING_KEY});
        table.put("no url", new String[] {"merchants:\n  - id: a\n    name: A\n", MerchantsFile.MISSING_KEY});
        table.put("an id with a space", new String[] {"merchants:\n" + merchant("\"a b " + CANARY + "\"", ""), MerchantsFile.BAD_ID});
        table.put("a 101-character id", new String[] {"merchants:\n" + merchant("a".repeat(101), ""), MerchantsFile.BAD_ID});
        table.put("a repeated id", new String[] {"merchants:\n" + merchant("a", "") + merchant("a", ""), MerchantsFile.DUPLICATE_ID});
        table.put("a blank name", new String[] {"merchants:\n  - id: a\n    name: \" \"\n    url: http://127.0.0.1:1\n", MerchantsFile.WRONG_TYPE});
        table.put("a numeric id", new String[] {"merchants:\n" + merchant("7", ""), MerchantsFile.WRONG_TYPE});
        table.put("an ftp url", new String[] {"merchants:\n  - id: a\n    name: A\n    url: ftp://" + CANARY + ".example\n", MerchantsFile.BAD_URL});
        table.put("a url with user info", new String[] {"merchants:\n  - id: a\n    name: A\n    url: http://u:" + CANARY + "@127.0.0.1:1\n", MerchantsFile.BAD_URL});
        table.put("a url with a query", new String[] {"merchants:\n  - id: a\n    name: A\n    url: http://127.0.0.1:1/?k=" + CANARY + "\n", MerchantsFile.BAD_URL});
        table.put("a url with a fragment", new String[] {"merchants:\n  - id: a\n    name: A\n    url: http://127.0.0.1:1/#" + CANARY + "\n", MerchantsFile.BAD_URL});
        table.put("a url with no host", new String[] {"merchants:\n  - id: a\n    name: A\n    url: http:///x\n", MerchantsFile.BAD_URL});
        table.put("a status of 'paused'", new String[] {"merchants:\n" + merchant("a", "    status: paused-" + CANARY + "\n"), MerchantsFile.BAD_STATUS});
        table.put("a lab_url that is not http", new String[] {"merchants:\n" + merchant("a", "    lab_url: file:///" + CANARY + "\n"), MerchantsFile.BAD_URL});
        table.put("endpoint_hosts not a list", new String[] {"merchants:\n" + merchant("a", "    endpoint_hosts: " + CANARY + "\n"), MerchantsFile.WRONG_TYPE});
        table.put("an endpoint host with a path", new String[] {"merchants:\n" + merchant("a", "    endpoint_hosts: [\"x.example/" + CANARY + "\"]\n"), MerchantsFile.BAD_ENDPOINT_HOST});
        table.put("an empty endpoint host", new String[] {"merchants:\n" + merchant("a", "    endpoint_hosts: [\"\"]\n"), MerchantsFile.BAD_ENDPOINT_HOST});
        table.put("ucp_versions not a list", new String[] {"merchants:\n" + merchant("a", "    ucp_versions: 2026-08-25\n"), MerchantsFile.WRONG_TYPE});
        table.put("an unquoted date version", new String[] {"merchants:\n" + merchant("a", "    ucp_versions: [2026-08-25]\n"), MerchantsFile.WRONG_TYPE});
        table.put("an empty ucp_versions", new String[] {"merchants:\n" + merchant("a", "    ucp_versions: []\n"), MerchantsFile.BAD_VERSION});
        table.put("a version the agent does not speak", new String[] {"merchants:\n" + merchant("a", "    ucp_versions: [\"2031-01-01\"]\n"), MerchantsFile.BAD_VERSION});
        table.put("payment_handler not a map", new String[] {"merchants:\n" + merchant("a", "    payment_handler: " + CANARY + "\n"), MerchantsFile.WRONG_TYPE});
        table.put("payment_handler without a key", new String[] {"merchants:\n" + merchant("a", "    payment_handler:\n      id: h\n      type: card\n      credential_type: token\n"), MerchantsFile.BAD_HANDLER});
        table.put("payment_handler with an unknown key", new String[] {"merchants:\n" + merchant("a", "    payment_handler:\n      id: h\n      type: card\n      credential_type: token\n      token_ref: env:X\n      " + CANARY + ": 1\n"), MerchantsFile.UNKNOWN_KEY});
        table.put("a token in place of a token_ref", new String[] {"merchants:\n" + merchant("a", "    payment_handler:\n      id: h\n      type: card\n      credential_type: token\n      token_ref: tok_" + CANARY + "\n"), MerchantsFile.BAD_TOKEN_REF});
        table.put("a token_ref for a lowercase variable", new String[] {"merchants:\n" + merchant("a", "    payment_handler:\n      id: h\n      type: card\n      credential_type: token\n      token_ref: env:lower\n"), MerchantsFile.BAD_TOKEN_REF});
        // H5 C9: transport: mcp is read now; mcp alone lacks its credentials (MerchantsFileH5Test). completion: handoff is
        // accepted by the file and refused by the stack: load_completionHandoff_isReadButTheStackRefusesIt.
        table.put("transport: mcp", new String[] {"merchants:\n" + merchant("a", "    transport: mcp\n"), MerchantsFile.MCP_INCOMPLETE});
        table.put("transport of the wrong type", new String[] {"merchants:\n" + merchant("a", "    transport: 3\n"), MerchantsFile.WRONG_TYPE});
        table.put("unparsable yaml", new String[] {"merchants: [unclosed " + CANARY + "\n", MerchantsFile.UNREADABLE});

        table.forEach((name, row) -> {
            assertThatThrownBy(() -> load(row[0], AgentProfile.LOCAL)).as(name)
                    .isInstanceOf(IllegalStateException.class).hasMessage(row[1]).message().doesNotContain(CANARY)
                    .doesNotContain(dir.toString());
        });
        assertThatThrownBy(() -> MerchantsFile.load(dir.resolve("missing.yaml"), AgentProfile.LOCAL))
                .hasMessage(MerchantsFile.UNREADABLE);
    }

    // Covers: 19, 7.2, H5 C9 (the completion: handoff row: the file accepts it, and the stack refuses it with the
    // temporary reason until C7 adds the handoff path; C7 removes the refusal and changes this row in the same commit)
    @Test
    void load_completionHandoff_isReadButTheStackRefusesIt() throws IOException {
        MerchantSettings m = load("merchants:\n" + merchant("a", "    completion: handoff\n"), AgentProfile.LOCAL)
                .getFirst();

        assertThat(m.completion()).isEqualTo(dev.ucplab.core.merchant.Completion.HANDOFF);
        assertThatThrownBy(() -> MerchantWiring.requireBuildable(m)).isInstanceOf(IllegalStateException.class)
                .hasMessage(MerchantWiring.HANDOFF_NOT_WIRED);
    }

    // Covers: 19, 23.3 (lab_url is a local-profile key: in demo and prod the lab routes do not exist, so the key is refused)
    @Test
    void load_labUrl_isRefusedOutsideTheLocalProfile() throws IOException {
        String yaml = "merchants:\n" + merchant("a", "    lab_url: http://127.0.0.1:9001\n");

        assertThat(load(yaml, AgentProfile.LOCAL)).hasSize(1);
        assertThatThrownBy(() -> load(yaml, AgentProfile.PROD)).hasMessage(MerchantsFile.LAB_URL_NOT_LOCAL);
        assertThatThrownBy(() -> load(yaml, AgentProfile.DEMO)).hasMessage(MerchantsFile.LAB_URL_NOT_LOCAL);
        assertThat(load("merchants:\n" + merchant("a", ""), AgentProfile.PROD)).hasSize(1);
    }

    // Covers: 19 (the default of 'status' is active; both values parse; endpoint hosts and the URL are kept as written)
    @Test
    void load_statusValues_andDefaults() throws IOException {
        assertThat(load("merchants:\n" + merchant("a", "    status: active\n") + merchant("b", "    status: suspended\n"),
                AgentProfile.LOCAL)).extracting(MerchantSettings::fileStatus)
                .containsExactly(MerchantStatus.ACTIVE, MerchantStatus.SUSPENDED);
    }

    // Covers: 19, 26.2 (the versions the agent speaks are accepted in a pin; the pin is kept as a set)
    @Test
    void load_aPinOfSupportedVersions_isAccepted() throws IOException {
        assertThat(load("merchants:\n" + merchant("a", "    ucp_versions: [\"2026-04-08\"]\n"), AgentProfile.LOCAL).get(0)
                .ucpVersions()).isEqualTo(Set.of("2026-04-08"));
    }
}
