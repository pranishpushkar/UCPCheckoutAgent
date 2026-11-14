package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.runtime.ModelClients;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * m5-plan section 10, C16: nothing in {@code ./mvnw verify} reaches the live model. Live tests are {@code *IT} classes,
 * run only under {@code -Peval-live} or {@code -Plive-spike}; no other test class of any module names the live lookup.
 */
class NoLiveInVerifyTest {

    private static final String LIVE_LOOKUP = "ModelClients.load(\"" + "gemini\"";

    /**
     * The one test allowed to look the client up by name (m6-plan C1): agent-service reaches agent-adk at runtime scope
     * only, so the lookup is the only way to prove the provider works on that module's classpath. It is offline: it
     * must point the client at a loopback stub and use a fake key, which {@link #allowedLookup_staysOffline} checks.
     */
    private static final String ALLOWED = "agent-service/src/test/java/dev/ucplab/service/LiveProviderClasspathTest.java";

    // Covers: R4, 15, I8 (no test class outside the live profiles looks up the Gemini client by name)
    @Test
    void testSources_outsideLiveProfiles_neverLoadTheGeminiClient() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(p -> p.toString().replace('\\', '/').contains("/src/test/java/"))
                    .filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().endsWith("IT.java"))
                    .toList();
        }

        assertThat(sources).as("test sources found under %s", root).hasSizeGreaterThan(100);
        for (Path source : sources) {
            String relative = root.relativize(source).toString().replace('\\', '/');
            if (relative.equals(ALLOWED)) {
                continue;
            }
            assertThat(Files.readString(source)).as(relative).doesNotContain(LIVE_LOOKUP);
        }
    }

    // Covers: R4, I8 (the one allowed lookup talks only to 127.0.0.1 with a fake key, never the real API or key)
    @Test
    void allowedLookup_staysOffline() throws IOException {
        String text = Files.readString(Path.of("..").toAbsolutePath().normalize().resolve(ALLOWED));

        assertThat(text).contains(LIVE_LOOKUP)
                .contains(".withBaseUrl(URI.create(\"http://127.0.0.1:")
                .contains("ModelKey.of(FAKE_KEY)")
                .doesNotContain("getenv")
                .doesNotContain("GEMINI_API_KEY");
    }

    // Covers: R4, Decision 1 (without -Peval-live the harness has no live provider on its classpath)
    @Test
    void harnessClasspath_hasNoLiveProvider() {
        assertThat(ModelClients.available(EvalMain.LIVE_PROVIDER)).isFalse();
    }
}
