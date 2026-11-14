package dev.ucplab.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.config.AgentProfile;
import dev.ucplab.core.policy.PolicyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The startup checks on the settings (9.2, 9.1 P6, 18, D10, I8): each refusal has a fixed message naming the setting,
 * never its value.
 */
class ServicePropertiesTest {

    private static final String REAL_SECRET = "s3cr3t-value-0123456789abcdefghijklmnop";

    @TempDir
    Path dir;

    private Map<String, String> env(String profile) {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_PROFILE", profile);
        env.put("DATABASE_URL", "jdbc:sqlite:" + dir.resolve("p.db"));
        env.put("APPROVAL_SECRET", REAL_SECRET);
        return env;
    }

    private static ServiceProperties from(Map<String, String> env) {
        return ServiceProperties.from(Environment.of(env));
    }

    // Covers: 9.2, I8 (prod refuses a change-me placeholder secret; the message names the setting, not the value)
    @Test
    void prod_placeholderSecret_isRefused() {
        Map<String, String> env = env("prod");
        env.put("APPROVAL_SECRET", "change-me-local-only-at-least-32-bytes");

        assertThatThrownBy(() -> from(env)).isInstanceOf(IllegalStateException.class)
                .hasMessage(ServiceProperties.SECRET_PLACEHOLDER).message().contains("APPROVAL_SECRET")
                .doesNotContain("change-me-local");
    }

    // Covers: 9.2 (the same placeholder is accepted in local, where .env.example's value is meant to work)
    @Test
    void local_placeholderSecret_isAccepted() {
        Map<String, String> env = env("local");
        env.put("APPROVAL_SECRET", "change-me-local-only-at-least-32-bytes");

        assertThat(from(env).profile()).isEqualTo(AgentProfile.LOCAL);
    }

    // Covers: 9.2, I8 (a secret shorter than 32 bytes is refused in every profile; the value is not in the message)
    @Test
    void shortSecret_isRefusedInEveryProfile() {
        for (String profile : new String[] {"local", "prod"}) {
            Map<String, String> env = env(profile);
            env.put("APPROVAL_SECRET", "short-secret-31-bytes-xxxxxxxxx");

            assertThatThrownBy(() -> from(env)).hasMessage(ServiceProperties.SECRET_SHORT).message()
                    .doesNotContain("short-secret");
        }
    }

    // Covers: 9.2 (no approval secret at all refuses startup)
    @Test
    void missingSecret_isRefused() {
        Map<String, String> env = env("local");
        env.remove("APPROVAL_SECRET");

        assertThatThrownBy(() -> from(env)).hasMessage(ServiceProperties.SECRET_REQUIRED);
    }

    // Covers: 9.1 P6 (prod refuses a policy file that turns approval off; local accepts it)
    @Test
    void prod_approvalRequiredFalse_isRefused() throws IOException {
        Path policy = Files.writeString(dir.resolve("policy.yaml"), "approval_required: false\n");
        Map<String, String> prod = env("prod");
        prod.put("POLICY_FILE", policy.toString());
        Map<String, String> local = env("local");
        local.put("POLICY_FILE", policy.toString());

        assertThatThrownBy(() -> from(prod)).hasMessage(ServiceProperties.APPROVAL_OFF);
        assertThat(from(local).policy().approvalRequired()).isFalse();
    }

    // Covers: 9.1 (POLICY_FILE is loaded; without it the 9.1 defaults apply)
    @Test
    void policyFile_isLoaded_andDefaultsApplyWithoutOne() throws IOException {
        Path policy = Files.writeString(dir.resolve("policy.yaml"), "per_purchase_cap_minor: 9000\nmax_lines: 3\n");
        Map<String, String> env = env("prod");
        env.put("POLICY_FILE", policy.toString());

        PolicyConfig loaded = from(env).policy();

        assertThat(loaded.perPurchaseCapMinor()).isEqualTo(9000);
        assertThat(loaded.maxLines()).isEqualTo(3);
        assertThat(loaded.dailyCapMinor()).isEqualTo(PolicyConfig.defaults().dailyCapMinor());
        assertThat(from(env("prod")).policy()).isEqualTo(PolicyConfig.defaults());
    }

    // Covers: D10 (demo refuses to start in M6)
    @Test
    void demo_isRefused() {
        assertThatThrownBy(() -> from(env("demo"))).hasMessage(ProfileGuard.DEMO_REFUSED);
    }

    // Covers: section 15, I8 (an unknown AGENT_PROFILE is refused without echoing it)
    @Test
    void unknownProfile_isRefused() {
        assertThatThrownBy(() -> from(env("local,prod"))).hasMessage(ServiceProperties.PROFILE_INVALID);
        assertThatThrownBy(() -> from(env("staging-x"))).message().doesNotContain("staging-x");
    }

    // Covers: 25.4 (DATABASE_URL must be a SQLite file URL, and prod requires it)
    @Test
    void databaseUrl_mustBeASqliteFile_andProdRequiresIt() {
        Map<String, String> postgres = env("local");
        postgres.put("DATABASE_URL", "jdbc:postgresql://db:5432/ucplab?password=hunter2");
        Map<String, String> memory = env("local");
        memory.put("DATABASE_URL", "jdbc:sqlite::memory:");
        Map<String, String> prodWithout = env("prod");
        prodWithout.remove("DATABASE_URL");

        assertThatThrownBy(() -> from(postgres)).hasMessage(ServiceProperties.DATABASE_INVALID).message()
                .doesNotContain("hunter2");
        assertThatThrownBy(() -> from(memory)).hasMessage(ServiceProperties.DATABASE_INVALID);
        assertThatThrownBy(() -> from(prodWithout)).hasMessage(ServiceProperties.DATABASE_REQUIRED);
        assertThat(from(env("local")).databaseFile()).isEqualTo(dir.resolve("p.db"));
    }

    // Covers: I8 (neither the settings nor the environment print a value)
    @Test
    void toString_showsNoValue() {
        Map<String, String> env = env("local");
        env.put("GEMINI_API_KEY", "AIza-canary-key-value");

        assertThat(from(env).toString()).doesNotContain(REAL_SECRET);
        assertThat(Environment.of(env).toString()).doesNotContain(REAL_SECRET).doesNotContain("AIza-canary")
                .doesNotContain("GEMINI");
    }

    // Covers: 18.8 (LOCAL_USERS defaults to alice and bob; a name that is not plain lowercase, or a repeat, is refused)
    @Test
    void localUsers_defaultAndValidation() {
        Map<String, String> custom = env("local");
        custom.put("LOCAL_USERS", "carol, dave");

        assertThat(from(env("local")).localUsers()).containsExactly("alice", "bob");
        assertThat(from(custom).localUsers()).containsExactly("carol", "dave");
        for (String bad : new String[] {"alice,,bob", "Alice", "local-alice", "alice,alice", "a b"}) {
            Map<String, String> env = env("local");
            env.put("LOCAL_USERS", bad);
            assertThatThrownBy(() -> from(env)).as(bad).hasMessage(ServiceProperties.USERS_INVALID);
        }
    }

    // Covers: m6-plan section 4 (PUBLIC_ORIGIN is scheme, host and port only; a path, query or user info is refused)
    @Test
    void publicOrigin_defaultAndValidation() {
        Map<String, String> custom = env("local");
        custom.put("PUBLIC_ORIGIN", "https://lab.example:8443");

        Map<String, String> two = env("local");
        two.put("PUBLIC_ORIGIN", "https://a.example, http://b.example:81");

        assertThat(from(env("local")).publicOrigins())
                .containsExactly("http://localhost:8080", "http://127.0.0.1:8080");
        assertThat(from(custom).publicOrigins()).containsExactly("https://lab.example:8443");
        assertThat(from(two).publicOrigins()).containsExactly("https://a.example", "http://b.example:81");
        for (String bad : new String[] {"localhost:8080", "http://localhost:8080/", "http://u@localhost",
                "http://localhost/?q", "ftp://localhost"}) {
            Map<String, String> env = env("local");
            env.put("PUBLIC_ORIGIN", bad);
            assertThatThrownBy(() -> from(env)).as(bad).hasMessage(ServiceProperties.ORIGIN_INVALID);
        }
    }
}
