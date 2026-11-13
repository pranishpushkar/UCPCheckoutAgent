package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeakScanTest {

    private static final String FAKE_KEY = "AIzaFAKE-not-a-real-key-0123456789abcdef";

    private static Map<String, String> forbidden(String canary) {
        return Map.of(FAKE_KEY, "the model key", canary, "the canary");
    }

    private static void writeOutput(Path out) throws Exception {
        Files.createDirectories(out.resolve("evidence/A-S12/run-0"));
        Files.writeString(out.resolve("report.md"), "# Live evaluation report\n");
        Files.writeString(out.resolve("evidence/A-S12/run-0/run.log"), "nothing secret\n");
    }

    // Covers: I8, R4 (a clean live output passes the scan and is kept)
    @Test
    void enforce_clean_keepsTheOutput(@TempDir Path out) throws Exception {
        writeOutput(out);
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        boolean clean = LeakScan.enforce(out, "report: done", forbidden(Canary.generate()),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(clean).isTrue();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(out.resolve("evidence/A-S12/run-0/run.log")).exists();
    }

    // Covers: I8, R4 (m5-plan section 10 item 4: a planted key in an evidence file and the canary in the captured
    // output fail the scan, which names what and where, never the secret, and empties the output folder)
    @Test
    void enforce_plantedHits_failsAndDeletesTheEvidence(@TempDir Path out) throws Exception {
        String canary = Canary.generate();
        writeOutput(out);
        Files.writeString(out.resolve("evidence/A-S12/run-0/run.log"), "x-goog-api-key: " + FAKE_KEY + "\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        boolean clean = LeakScan.enforce(out, "session " + canary, forbidden(canary),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        String said = err.toString(StandardCharsets.UTF_8);
        assertThat(clean).isFalse();
        assertThat(said).contains("leak scan: the model key in evidence/A-S12/run-0/run.log")
                .contains("leak scan: the canary in stdout/stderr").doesNotContain(FAKE_KEY).doesNotContain(canary);
        assertThat(out).exists().isEmptyDirectory();
    }

    // Covers: I8 (the scan reads bytes, so a key inside a binary or non-UTF-8 file is still found)
    @Test
    void scan_nonUtf8File_stillFindsTheKey(@TempDir Path out) throws Exception {
        byte[] bytes = ("ÿþ" + FAKE_KEY).getBytes(StandardCharsets.ISO_8859_1);
        Files.write(out.resolve("blob.bin"), bytes);

        assertThat(LeakScan.scan(out, "", forbidden(Canary.generate()))).containsExactly("the model key in blob.bin");
    }
}
