package dev.ucplab.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The mutation workflow stays manual, read-only, secret-free and pinned (ADR 0009, plan Q6). Reads the file; runs nothing. */
class MutationWorkflowTest {

    private static String workflow() throws IOException {
        return Files.readString(Path.of("..", ".github", "workflows", "mutation.yml"));
    }

    // Covers: ADR 0009 (never on a pull request or a push, a read-only token, no secrets, no cache)
    @Test
    void theWorkflow_isManualOnly_readOnly_withNoSecretsAndNoCache() throws IOException {
        String w = workflow();
        String code = String.join("\n", w.lines().filter(l -> !l.stripLeading().startsWith("#")).toList());

        assertThat(code).contains("workflow_dispatch:").doesNotContain("pull_request").doesNotContain("push:").doesNotContain("schedule:");
        assertThat(code).contains("permissions:\n  contents: read");
        assertThat(code).doesNotContain("secrets.").doesNotContain("cache:").doesNotContain("pull_request_target");
        assertThat(code).contains("java-version: '21'").contains("timeout-minutes: ${{ matrix.pass == 'core-orchestrator' && 120 || 30 }}");
    }

    // Covers: ADR 0009 (every action pinned by a 40-character commit SHA; every checkout drops its credentials)
    @Test
    void everyAction_isPinnedBySha_andEveryCheckoutDropsItsCredentials() throws IOException {
        String w = workflow();
        List<String> uses = w.lines().map(String::strip).filter(l -> l.startsWith("- uses: ")).toList();
        Pattern pinned = Pattern.compile("- uses: [\\w.-]+/[\\w.-]+@[0-9a-f]{40}(\\s+#.*)?");

        assertThat(uses).isNotEmpty().allSatisfy(u -> assertThat(pinned.matcher(u).matches()).as(u).isTrue());
        Matcher checkout = Pattern.compile("actions/checkout@").matcher(w);
        int checkouts = 0;
        while (checkout.find()) {
            checkouts++;
        }
        assertThat(w.split("persist-credentials: false", -1).length - 1).isEqualTo(checkouts);
    }

    // Covers: ADR 0009 (one job per pass; the report job needs them all and runs the generator)
    @Test
    void theMatrix_hasOneJobPerPass_andTheReportJobRunsTheGenerator() throws IOException {
        String w = workflow();

        for (String pass : List.of("core-policy", "core-approval", "core-merchant", "core-json", "core-intent", "core-recovery",
                "core-orchestrator", "ucp", "persistence")) {
            assertThat(w).contains(pass);
            assertThat(Files.readString(Path.of("..", "scripts", "mutation-pass.sh"))).contains(pass + ")");
        }
        assertThat(w).contains("needs: pass").contains("dev.ucplab.eval.report.MutationReport");
    }
}
