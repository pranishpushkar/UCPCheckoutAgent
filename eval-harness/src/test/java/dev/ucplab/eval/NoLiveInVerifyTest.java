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
            assertThat(Files.readString(source)).as(root.relativize(source).toString()).doesNotContain(LIVE_LOOKUP);
        }
    }

    // Covers: R4, Decision 1 (without -Peval-live the harness has no live provider on its classpath)
    @Test
    void harnessClasspath_hasNoLiveProvider() {
        assertThat(ModelClients.available(EvalMain.LIVE_PROVIDER)).isFalse();
    }
}
