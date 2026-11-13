package dev.ucplab.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.runtime.BudgetLimit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The CHECK lists on {@code agent_run_tool_event.summary} and {@code agent_run.reason} (V5) cover every code the runtime
 * can produce, read from the runtime's source and the budget enum rather than typed here, and nothing else.
 */
class RunCodesWiringTest {

    private static final Path RUNTIME = Path.of("../agent-runtime/src/main/java/dev/ucplab/runtime");
    private static final Path V5 = Path.of("../agent-persistence/src/main/resources/db/migration/V5__agent_run.sql");
    private static final Pattern LITERAL = Pattern.compile("\"([a-z_]+)\"");

    private static List<String> runtimeLines() throws IOException {
        try (Stream<Path> files = Files.walk(RUNTIME)) {
            return files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return Files.readAllLines(p).stream();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    private static Set<String> literals(List<String> lines, Pattern lineFilter) {
        Set<String> out = new TreeSet<>();
        for (String line : lines) {
            if (lineFilter.matcher(line).find()) {
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    private static Set<String> checkList(String column) throws IOException {
        Matcher m = Pattern.compile(column + " TEXT (?:NOT NULL )?CHECK \\(" + column + " IN \\(([^)]*)\\)")
                .matcher(Files.readString(V5).replaceAll("--[^\n]*", ""));
        assertThat(m.find()).as("CHECK on " + column).isTrue();
        return new TreeSet<>(Arrays.stream(m.group(1).split(",")).map(s -> s.replace("'", "").strip()).toList());
    }

    // Covers: I8, 23.1, m6-plan section 3.5 (every tool code the runtime produces is in the summary CHECK, and the CHECK
    // lists nothing else)
    @Test
    void summaryCheck_isExactlyTheToolOutcomeCodes() throws IOException {
        Set<String> fromCode = literals(runtimeLines(), Pattern.compile(
                "ToolOutcome\\.error\\(|new ToolOutcome\\.Result\\(|return \"[a-z_]+\";|\"invalid_argument\""));
        // "error" and "detail" are the JSON keys on those same lines, not codes.
        fromCode.removeAll(Set.of("error", "detail"));

        assertThat(fromCode).contains("ok", "invalid_argument", "unavailable", "not_found", "unknown_tool", "no_answer");
        assertThat(checkList("summary")).isEqualTo(fromCode);
    }

    // Covers: I8, 23.1 (every reason the runtime writes, and every budget limit's name, is in the reason CHECK; the
    // CHECK adds only the service's own "interrupted")
    @Test
    void reasonCheck_coversTheRuntimeReasonsAndBudgetLimits() throws IOException {
        Set<String> fromCode = literals(runtimeLines(), Pattern.compile(
                "Failed\\(\"|finishFailed\\(\"|INTERNAL_ERROR = "));
        Arrays.stream(BudgetLimit.values()).map(Enum::name).forEach(fromCode::add);

        assertThat(fromCode).contains("model_error", "tool_error", "internal_error", "script_drift", "WALL_CLOCK");
        Set<String> check = checkList("reason");
        assertThat(check).containsAll(fromCode);
        assertThat(check).containsExactlyInAnyOrderElementsOf(unionWith(fromCode, Set.of(RunSupervisor.INTERRUPTED)));
    }

    private static Set<String> unionWith(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
