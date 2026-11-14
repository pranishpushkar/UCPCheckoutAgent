package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * H1 C7, DESIGN 26.5 and the plan's section 7: third-party code never runs inside {@code ./mvnw verify}. The code that
 * starts the sample merchant ({@code PinnedSampleLauncher}) and the program that runs the rows ({@code InteropMain}) are
 * referenced by no test source, and the build starts the program only inside the {@code interop} profile.
 */
class NoInteropInVerifyTest {

    private static final List<String> REAL = List.of("PinnedSampleLauncher", "InteropMain", "interop-fetch");
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    // Covers: 26.5, NFR (no test source of any module names the real launcher, the rows' main class or the fetch script)
    @Test
    void noTestSource_namesTheRealLauncher() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            sources = walk.filter(p -> p.toString().replace('\\', '/').contains("/src/test/java/"))
                    .filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("NoInteropInVerifyTest.java"))
                    .toList();
        }

        assertThat(sources).hasSizeGreaterThan(100);
        for (Path source : sources) {
            String text = Files.readString(source);
            for (String real : REAL) {
                assertThat(text).as(ROOT.relativize(source) + " must not name " + real).doesNotContain(real);
            }
        }
    }

    // Covers: 26.5 (the rows' main class appears in the pom only inside the interop profile; verify's own configuration
    // never starts it, and that profile is the only one that does)
    @Test
    void thePom_startsTheRows_onlyInsideTheInteropProfile() throws IOException {
        String pom = Files.readString(ROOT.resolve("eval-harness/pom.xml"));
        int profile = pom.indexOf("<id>interop</id>");
        assertThat(profile).as("an interop profile exists").isPositive();
        int end = pom.indexOf("</profile>", profile);

        int at = -1;
        int found = 0;
        while ((at = pom.indexOf("InteropMain", at + 1)) >= 0) {
            found++;
            assertThat(at).as("InteropMain at offset %d is inside the interop profile", at).isBetween(profile, end);
        }
        assertThat(found).isPositive();
        assertThat(pom.indexOf("<id>interop</id>", end)).as("one interop profile").isNegative();
    }
}
