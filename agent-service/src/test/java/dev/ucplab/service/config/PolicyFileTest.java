package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.policy.CurrencyCaps;
import dev.ucplab.core.policy.PolicyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code POLICY_FILE} (9.1): every setting by its snake-case key, refusals fixed and never echoing the file (I8). */
class PolicyFileTest {

    @TempDir
    Path dir;

    private PolicyConfig load(String yaml) throws IOException {
        return PolicyFile.load(Files.writeString(dir.resolve("policy.yaml"), yaml));
    }

    // Covers: 9.1 (every 9.1 setting is read from its key)
    @Test
    void load_everyKey_isRead() throws IOException {
        PolicyConfig c = load("""
                per_purchase_cap_minor: 1000
                daily_cap_minor: 3000
                max_quantity_per_line: 2
                max_lines: 4
                max_proposals_per_hour: 6
                approval_required: true
                max_requotes: 0
                """);

        assertThat(c).isEqualTo(new PolicyConfig(1000, 3000, 2, 4, 6, true, 0));
    }

    // Covers: 9.1 caps per currency, 26.7 (caps: gives P2 and P3 per currency, each in its own minor units; the other
    // settings keep their keys)
    @Test
    void load_capsPerCurrency_areRead() throws IOException {
        PolicyConfig c = load("""
                caps:
                  USD: {per_purchase_minor: 200000, daily_minor: 400000}
                  INR: {per_purchase_minor: 20000000, daily_minor: 40000000}
                max_lines: 4
                """);

        assertThat(c.caps()).isEqualTo(Map.of("USD", new CurrencyCaps(200_000, 400_000),
                "INR", new CurrencyCaps(20_000_000, 40_000_000)));
        assertThat(c.maxLines()).isEqualTo(4);
    }

    // Covers: 9.1 caps per currency (the flat keys still load, as the USD caps: an INR quote then has no caps)
    @Test
    void load_flatKeys_areTheUsdCaps() throws IOException {
        PolicyConfig c = load("per_purchase_cap_minor: 1000\ndaily_cap_minor: 3000\n");

        assertThat(c.caps()).isEqualTo(Map.of("USD", new CurrencyCaps(1000, 3000)));
        assertThat(c.capsFor("INR")).isEmpty();
    }

    // Covers: 9.1 caps per currency, I8 (caps and a flat key together, a currency that is not three upper-case letters,
    // a currency without both caps, an unknown key in a currency, a wrong type and a non-positive cap are refused with
    // fixed messages that echo nothing from the file)
    @Test
    void load_badCaps_areRefusedWithFixedMessages() {
        assertThatThrownBy(() -> load("per_purchase_cap_minor: 1000\ncaps:\n  USD: {per_purchase_minor: 1, daily_minor: 2}\n"))
                .hasMessage(PolicyFile.CAPS_TWICE);
        assertThatThrownBy(() -> load("caps:\n  inr-canary: {per_purchase_minor: 1, daily_minor: 2}\n"))
                .hasMessage(PolicyFile.OUT_OF_RANGE).message().doesNotContain("inr-canary");
        assertThatThrownBy(() -> load("caps:\n  INR: {per_purchase_minor: 1}\n")).hasMessage(PolicyFile.CAPS_INCOMPLETE);
        assertThatThrownBy(() -> load("caps:\n  INR: {per_purchase_minor: 1, daily_minor: 2, fx_canary: 3}\n"))
                .hasMessage(PolicyFile.UNKNOWN_KEY).message().doesNotContain("fx_canary");
        assertThatThrownBy(() -> load("caps:\n  INR: {per_purchase_minor: lots-canary, daily_minor: 2}\n"))
                .hasMessage(PolicyFile.WRONG_TYPE).message().doesNotContain("lots-canary");
        assertThatThrownBy(() -> load("caps: [USD]\n")).hasMessage(PolicyFile.WRONG_TYPE);
        assertThatThrownBy(() -> load("caps: {}\n")).hasMessage(PolicyFile.OUT_OF_RANGE);
        assertThatThrownBy(() -> load("caps:\n  INR: {per_purchase_minor: 0, daily_minor: 2}\n"))
                .hasMessage(PolicyFile.OUT_OF_RANGE);
    }

    // Covers: 9.1 (an empty file is the 9.1 defaults)
    @Test
    void load_emptyFile_isTheDefaults() throws IOException {
        assertThat(load("")).isEqualTo(PolicyConfig.defaults());
    }

    // Covers: 9.1, I8 (an unknown key, a wrong type, a value out of range, a non-map and a missing file are refused
    // with fixed messages that echo nothing from the file)
    @Test
    void load_badFiles_areRefusedWithFixedMessages() {
        assertThatThrownBy(() -> load("max_lines: 3\nsecret_canary_key: 1\n")).hasMessage(PolicyFile.UNKNOWN_KEY)
                .message().doesNotContain("secret_canary_key");
        assertThatThrownBy(() -> load("max_lines: three-canary\n")).hasMessage(PolicyFile.WRONG_TYPE)
                .message().doesNotContain("three-canary");
        assertThatThrownBy(() -> load("approval_required: 1\n")).hasMessage(PolicyFile.WRONG_TYPE);
        assertThatThrownBy(() -> load("max_lines: 0\n")).hasMessage(PolicyFile.OUT_OF_RANGE);
        assertThatThrownBy(() -> load("- a\n- b\n")).hasMessage(PolicyFile.NOT_A_MAP);
        assertThatThrownBy(() -> load("max_lines: [unclosed\n")).hasMessage(PolicyFile.UNREADABLE);
        assertThatThrownBy(() -> PolicyFile.load(dir.resolve("missing.yaml"))).hasMessage(PolicyFile.UNREADABLE);
    }

    // Covers: 9.1 (YAML type tags cannot build objects: the safe constructor refuses them)
    @Test
    void load_typeTag_isRefused() {
        assertThatThrownBy(() -> load("max_lines: !!java.io.File [\"/tmp\"]\n")).hasMessage(PolicyFile.UNREADABLE);
    }
}
