package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The README (m6-plan section 13, C21): its quick-start commands are the commands of {@code scripts/quickstart.sh}, so
 * the README and the CI job cannot drift; its links to the three reports and to the repository's own files resolve; no
 * line sets the model key to a value; and the diagram is the one in docs/architecture.md. Covers: 16 (M6 acceptance:
 * "quick start works from a clean clone, README links the report"), I8.
 */
class ReadmeTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> REPORTS = List.of("docs/results/m4-scripted-report.md",
            "docs/results/m5-scripted-report.md", "docs/results/m5-live-report.md");

    private static String read(String name) throws IOException {
        return Files.readString(ROOT.resolve(name));
    }

    /** The commands (no blank lines, no comments) of the fenced block whose info string is {@code sh <tag>}. */
    private static List<String> block(String markdown, String tag) {
        Matcher m = Pattern.compile("(?m)^```sh " + tag + "\\n(.*?)^```", Pattern.DOTALL).matcher(markdown);
        assertThat(m.find()).as("a ```sh " + tag + " block in the README").isTrue();
        return commands(m.group(1));
    }

    private static List<String> commands(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    /** The commands between {@code # >>> name} and {@code # <<<} in the script. */
    private static List<String> region(String script, String name) {
        Matcher m = Pattern.compile("(?m)^# >>> " + name + "\\n(.*?)^# <<<", Pattern.DOTALL).matcher(script);
        assertThat(m.find()).as("a region " + name + " in scripts/quickstart.sh").isTrue();
        return commands(m.group(1));
    }

    // Covers: 16, 13 (the README's quick-start block and its teardown block equal the two regions of scripts/quickstart.sh,
    // which the CI job runs; the live step is in the README only and is not in the script)
    @Test
    void quickStartBlocks_equalTheScript() throws IOException {
        String readme = read("README.md");
        String script = read("scripts/quickstart.sh");

        assertThat(block(readme, "quickstart")).isEqualTo(region(script, "quickstart")).hasSize(3);
        assertThat(block(readme, "teardown")).isEqualTo(region(script, "teardown")).containsExactly("docker compose down -v");
        assertThat(script).doesNotContain("GEMINI").doesNotContain("read -rs");
        assertThat(block(readme, "live")).anyMatch(l -> l.startsWith("read -rs \"GEMINI_API_KEY?"))
                .anyMatch(l -> l.equals("AGENT_MODEL=gemini docker compose up -d agent-service"));
    }

    // Covers: 16 (the README links the three committed reports, and each resolves to a file that git tracks)
    @Test
    void reportLinks_resolveToCommittedFiles() throws IOException, InterruptedException {
        String readme = read("README.md");

        for (String report : REPORTS) {
            assertThat(readme).as("a link to " + report).contains("](" + report + ")");
            assertThat(ROOT.resolve(report)).as(report).isRegularFile();
            assertThat(Files.readString(ROOT.resolve(report))).as(report).isNotBlank();
            Process git = new ProcessBuilder("git", "ls-files", "--error-unmatch", report).directory(ROOT.toFile())
                    .redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            if (git.waitFor() != 0 && !Files.isDirectory(ROOT.resolve(".git"))) {
                continue;   // an unpacked source archive: no git to ask
            }
            assertThat(git.exitValue()).as(report + " is tracked by git").isZero();
        }
    }

    // Covers: 16 (every relative link of the README points at a file or folder that exists)
    @Test
    void everyRelativeLink_exists() throws IOException {
        Matcher m = Pattern.compile("\\]\\(([^)#]+)(?:#[^)]*)?\\)").matcher(read("README.md"));
        int seen = 0;
        while (m.find()) {
            String target = m.group(1);
            if (target.startsWith("http") || target.startsWith("mailto:")) {
                continue;
            }
            seen++;
            assertThat(ROOT.resolve(target)).as("README link " + target).exists();
        }
        assertThat(seen).isGreaterThanOrEqualTo(5);
    }

    // Covers: I8, 25.4 (no line of the README sets GEMINI_API_KEY to a value; .env.example leaves it empty with its comment
    // on a line of its own; compose.yaml passes it as a bare name; the scripts and the workflow set it to nothing; scripts/image-secret-scan.sh alone writes a canary key, on purpose, into a
    // throwaway .env that it removes)
    @Test
    void theKey_isNeverGivenAValue_inTheReadme_theEnvExample_orCompose() throws IOException {
        Pattern assigned = Pattern.compile("GEMINI_API_KEY\\s*=\\s*\\S");
        for (String line : read("README.md").split("\n")) {
            assertThat(assigned.matcher(line).find()).as("README line: " + line).isFalse();
        }
        List<String> env = List.of(read(".env.example").split("\n"));
        assertThat(env).contains("GEMINI_API_KEY=");
        assertThat(env).noneMatch(l -> assigned.matcher(l).find());
        List<String> compose = read("compose.yaml").lines().map(String::strip).toList();
        assertThat(compose).contains("- GEMINI_API_KEY");
        assertThat(compose).noneMatch(l -> assigned.matcher(l).find() || l.matches("GEMINI_API_KEY:\\s*\\S.*"));
        for (String script : List.of("scripts/quickstart.sh", "scripts/quickstart-smoke.sh",
                ".github/workflows/quickstart.yml")) {
            for (String line : read(script).split("\n")) {
                if (!line.strip().startsWith("#")) {
                    assertThat(assigned.matcher(line).find() && !line.contains("grep"))
                            .as(script + " line: " + line).isFalse();
                }
            }
        }
    }

    // Covers: 13 (the diagram in the README is the first diagram of docs/architecture.md)
    @Test
    void readmeDiagram_isTheArchitectureDiagram() throws IOException {
        Pattern mermaid = Pattern.compile("```mermaid\\n(.*?)```", Pattern.DOTALL);
        Matcher inReadme = mermaid.matcher(read("README.md"));
        Matcher inArchitecture = mermaid.matcher(read("docs/architecture.md"));

        assertThat(inReadme.find()).isTrue();
        assertThat(inArchitecture.find()).isTrue();
        assertThat(inReadme.group(1)).isEqualTo(inArchitecture.group(1));
        assertThat(inReadme.group(1)).contains("flowchart").contains("agent-service").contains("lab-net");
    }
}
