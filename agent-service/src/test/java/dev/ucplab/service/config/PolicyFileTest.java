package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.policy.PolicyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
