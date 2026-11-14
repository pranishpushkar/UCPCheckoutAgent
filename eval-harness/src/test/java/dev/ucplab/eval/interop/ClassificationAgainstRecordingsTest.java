package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * H1 C9c: the classification is tested against the real thing, not a canned file. docs/interop/wire holds the normalised
 * recordings of the real run (paths, types, statuses and codes only), docs/interop/differences.md the committed entries. The
 * recordings must be fully explained, and a plant at any recorded step of the sample (a new field, a changed status, a differing
 * error code, a differing relative path) must come out UNCLASSIFIED: no glob may swallow a new difference.
 */
class ClassificationAgainstRecordingsTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> ROWS = List.of("S0", "S5", "S6", "S7");

    private static Map<String, DifferenceReport.Entry> entries() throws IOException {
        return DifferenceReport.parse(Files.readString(ROOT.resolve("docs/interop/differences.md")));
    }

    private static List<WireStep> read(String merchant, String row) throws IOException {
        return WireFiles.read(ROOT.resolve("docs/interop/wire/" + merchant + "-" + row + ".json"));
    }

    // Covers: R1, 13.5 (the committed recordings and the committed entries agree: every raw difference is explained, every
    // computed entry explains something, and no ours is open)
    @Test
    void theCommittedRecordings_areFullyExplained() throws IOException {
        List<WireDiff.Diff> all = new ArrayList<>();
        for (String row : ROWS) {
            all.addAll(WireDiff.diff(row, read("lab", row), read("sample", row)));
        }

        var ev = DifferenceReport.evaluate(all, entries());

        assertThat(ev.violations()).isEmpty();
        assertThat(all).isNotEmpty();
    }

    // Covers: R1, 13.5 (no glob swallows a new difference: at every recorded step of every row a planted new field, a changed
    // status, a differing error code and a differing relative path each come out UNCLASSIFIED)
    @Test
    void everyPlant_comesOutUnclassified() throws IOException {
        var entries = entries();
        List<String> swallowed = new ArrayList<>();
        int plants = 0;
        for (String row : ROWS) {
            List<WireStep> sample = read("sample", row);
            // A step the lab never made has no field to plant against: give the lab a copy of it, so a plant is the only new difference.
            List<WireStep> lab = new ArrayList<>(read("lab", row));
            for (WireStep s : sample) {
                if (lab.stream().noneMatch(l -> l.step().equals(s.step()))) {
                    lab.add(s);
                }
            }
            Set<String> baseline = new HashSet<>();
            WireDiff.diff(row, lab, sample).forEach(d -> baseline.add(d.matchText()));
            for (int i = 0; i < sample.size(); i++) {
                WireStep s = sample.get(i);
                Map<String, String> withField = new LinkedHashMap<>(s.responseFields());
                withField.put("zz_planted", "string");
                List<WireStep> variants = new ArrayList<>();
                List<String> names = new ArrayList<>();
                variants.add(new WireStep(s.step(), s.path(), s.status(), s.requestFields(), withField, s.errorCode()));
                names.add("new field");
                variants.add(new WireStep(s.step(), s.path(), 599, s.requestFields(), s.responseFields(), s.errorCode()));
                names.add("changed status");
                variants.add(new WireStep(s.step(), "/planted/{id}", s.status(), s.requestFields(), s.responseFields(), s.errorCode()));
                names.add("differing path");
                boolean hasCode = !s.errorCode().isEmpty() || lab.stream().anyMatch(l -> l.step().equals(s.step()) && !l.errorCode().isEmpty());
                if (hasCode) {
                    variants.add(new WireStep(s.step(), s.path(), s.status(), s.requestFields(), s.responseFields(), "PLANTED_CODE"));
                    names.add("differing error code");
                }
                for (int v = 0; v < variants.size(); v++) {
                    List<WireStep> planted = new ArrayList<>(sample);
                    planted.set(i, variants.get(v));
                    List<WireDiff.Diff> fresh = WireDiff.diff(row, lab, planted).stream()
                            .filter(d -> !baseline.contains(d.matchText())).toList();
                    plants++;
                    assertThat(fresh).as("%s %s at %s produces a new difference", row, names.get(v), s.step()).isNotEmpty();
                    var ev = DifferenceReport.evaluate(fresh, entries);
                    String who = row + "/" + s.step() + " " + names.get(v);
                    ev.explained().forEach((text, by) -> swallowed.add(who + ": " + text + " swallowed by " + by));
                }
            }
        }

        assertThat(plants).isGreaterThan(40);
        assertThat(swallowed).isEmpty();
    }

    // Covers: I8 (the committed recordings hold no token, no Authorization and no payment value: only paths, types, statuses, codes)
    @Test
    void theCommittedRecordings_holdNoTokenOrCredential() throws IOException {
        try (var files = Files.list(ROOT.resolve("docs/interop/wire"))) {
            for (Path f : files.toList()) {
                assertThat(Files.readString(f)).as(f.getFileName().toString())
                        .doesNotContain("success_token", "fail_token", "tok_ok", "tok_decline", "Bearer", "123 Main St", "Springfield", "62704");
            }
        }
    }
}
