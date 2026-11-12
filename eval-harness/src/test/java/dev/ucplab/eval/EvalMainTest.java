package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line's exit codes (13.5): 0 for a clean run, 3 for configuration errors and an unmet --require-full. */
class EvalMainTest {

    private static final CommitInfo CLEAN = new CommitInfo("0123456789abcdef0123456789abcdef01234567", "test", false,
            "git");

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(CommitInfo commit, String... args) {
        return EvalMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), commit);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // Covers: 13.5, S0 (exit 0: one real S0 run writes both reports, and the Markdown says REDUCED first)
    @Test
    void evalMain_oneCleanS0Run_exitsZeroAndWritesTheReports(@TempDir Path dir) throws IOException {
        int code = run(CLEAN, "--mode", "scripted", "--runs", "1", "--scenario", "S0", "--time-scale", "4",
                "--lanes", "1", "--out", dir.toString());

        assertThat(code).as(err()).isEqualTo(Report.CLEAN);
        assertThat(dir.resolve("report.json")).exists();
        assertThat(Files.readAllLines(dir.resolve("report.md")).getFirst())
                .isEqualTo("**REDUCED: 1 runs per scenario, time scale 4 — not an acceptance report**");
        assertThat(Files.readString(dir.resolve("report.json"))).contains("\"code\": 0");
        assertThat(dir.resolve("evidence")).doesNotExist();
    }

    // Covers: 13.5 (exit 3: an unknown argument)
    @Test
    void evalMain_unknownArgument_exitsThree() {
        assertThat(run(CLEAN, "--fast")).isEqualTo(Report.HARNESS_ERROR);
        assertThat(err()).contains("unknown argument --fast", "usage:");
    }

    // Covers: 13.5, 13.1 (exit 3: a time scale that breaks an ordering)
    @Test
    void evalMain_timeScaleSix_exitsThree() {
        assertThat(run(CLEAN, "--time-scale", "6")).isEqualTo(Report.HARNESS_ERROR);
        assertThat(err()).contains("time scale k = 6 breaks");
    }

    // Covers: 13.5, 13.1 (exit 3: live mode needs the agent runtime)
    @Test
    void evalMain_liveMode_exitsThree() {
        assertThat(run(CLEAN, "--mode", "live")).isEqualTo(Report.HARNESS_ERROR);
        assertThat(err()).contains("only scripted runs exist");
    }

    // Covers: 13.5 (exit 3: --run needs exactly one scenario)
    @Test
    void evalMain_runWithoutScenario_exitsThree() {
        assertThat(run(CLEAN, "--run", "3")).isEqualTo(Report.HARNESS_ERROR);
    }

    // Covers: 13.5 (exit 3 before anything runs: --require-full with fewer than 200 runs and k other than 1)
    @Test
    void evalMain_requireFullWithAReducedConfig_exitsThreeWithoutRunning(@TempDir Path dir) {
        assertThat(run(CLEAN, "--require-full", "--runs", "20", "--time-scale", "4", "--out", dir.toString()))
                .isEqualTo(Report.HARNESS_ERROR);
        assertThat(err()).contains("20 runs per scenario, fewer than 200", "time scale 4, not 1");
        assertThat(dir.resolve("report.json")).doesNotExist();
    }

    // Covers: 13.5 (exit 3 before anything runs: --require-full on a dirty or unknown commit)
    @Test
    void evalMain_requireFullOnADirtyUnknownCommit_exitsThree(@TempDir Path dir) {
        CommitInfo dirty = new CommitInfo(CommitInfo.UNKNOWN, CommitInfo.UNKNOWN, true, "none");

        assertThat(run(dirty, "--require-full", "--runs", "200", "--time-scale", "1", "--out", dir.toString()))
                .isEqualTo(Report.HARNESS_ERROR);
        assertThat(err()).contains("the commit is unknown", "uncommitted changes");
    }
}
