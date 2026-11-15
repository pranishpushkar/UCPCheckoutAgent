package dev.ucplab.eval.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.eval.report.MutationReport.Entry;
import dev.ucplab.eval.report.MutationReport.Mutant;
import dev.ucplab.eval.report.MutationReport.Result;
import dev.ucplab.eval.report.MutationReport.Run;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The mutation report generator (ADR 0009): its three failures, on small planted XML. No PIT, no network. */
class MutationReportTest {

    private static final String STAR = "dev.ucplab.core.approval.DefaultApprovalService";
    private static final List<String> STARS = List.of(STAR, "dev.ucplab.ucp.Redactor");

    private static String mutation(String cls, String method, String status, String mutator, String description, int line) {
        return "<mutation detected='false' status='" + status + "'><sourceFile>X.java</sourceFile><mutatedClass>" + cls
                + "</mutatedClass><mutatedMethod>" + method.replace("<", "&lt;").replace(">", "&gt;") + "</mutatedMethod><lineNumber>" + line + "</lineNumber><mutator>"
                + "org.pitest.mutationtest.engine.gregor.mutators." + mutator + "</mutator><description>" + description
                + "</description></mutation>";
    }

    private static Run run(String... mutations) throws Exception {
        String xml = "<?xml version=\"1.0\"?><mutations>" + String.join("", mutations) + "</mutations>";
        List<Mutant> ms = MutationReport.readXml(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new Run("core-approval", Map.of("module", "agent-core", "classes", "dev.ucplab.core.approval.*",
                "mutators", "STRONGER", "tries", "500", "seed", "1", "wall.seconds", "115"), ms);
    }

    private static final String OPEN_SURVIVOR = mutation(STAR, "open", "SURVIVED", "RemoveConditionalMutator_EQUAL_IF",
            "removed conditional - replaced equality check with true", 77);

    private static List<Entry> entries(String md) {
        return MutationReport.readEntries(md);
    }

    private static String entry(int n, String cls, String method, String mutator, String description, boolean approved) {
        return "### " + n + ". " + cls + "." + method + " " + mutator + " (a note)\n- description: " + description
                + " (a note)\n- why it is equivalent: x\n- the test that shows it: y\n" + (approved ? "- approved: owner 2026-11-15\n" : "") + "\n";
    }

    private Result build(Run run, String entriesMd) {
        return MutationReport.build(List.of(run), entries(entriesMd), STARS, "abc123", "1.30.2", "1.2.3");
    }

    // Covers: ADR 0009 (a star survivor that an approved entry accounts for passes, and the report shows the commit, the versions,
    // the run and the counts)
    @Test
    void anApprovedEntry_accountsForAStarSurvivor() throws Exception {
        Run r = run(OPEN_SURVIVOR, mutation(STAR, "open", "KILLED", "MathMutator", "Replaced integer addition with subtraction", 80));

        Result result = build(r, entry(1, STAR, "open", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", true));

        assertThat(result.violations()).isEmpty();
        assertThat(result.markdown()).contains("`abc123`", "PIT 1.30.2", "pitest-junit5-plugin 1.2.3", "| core-approval | agent-core |",
                "| core.approval.DefaultApprovalService | ★ | 2 | 1 | 0 | 1 | 0 | 1 |", "| 115 s |");
    }

    // Covers: ADR 0009 (planted: a star survivor with no entry fails, naming the mutant)
    @Test
    void aStarSurvivor_withNoEntry_fails() throws Exception {
        Result result = build(run(OPEN_SURVIVOR), "");

        assertThat(result.violations()).singleElement().asString()
                .contains("star survivor").contains(STAR + ".open:77").contains("RemoveConditionalMutator_EQUAL_IF");
    }

    // Covers: ADR 0009 (planted: a star mutant with no coverage fails the same way; one outside the star list does not)
    @Test
    void aStarNoCoverageMutant_fails_aNonStarOneDoesNot() throws Exception {
        Run star = run(mutation(STAR, "decide", "NO_COVERAGE", "VoidMethodCallMutator", "removed call to X::y", 5));
        Run other = run(mutation("dev.ucplab.core.intent.IntentId", "<init>", "SURVIVED", "RemoveConditionalMutator_EQUAL_IF", "d", 5));

        assertThat(build(star, "").violations()).singleElement().asString().contains("NO_COVERAGE").contains("decide");
        assertThat(build(other, "").violations()).isEmpty();
    }

    // Covers: ADR 0009 (planted: an entry without an `approved:` line fails, naming the entry, and does not account for the mutant)
    @Test
    void anEntryWithoutApproval_fails() throws Exception {
        Result result = build(run(OPEN_SURVIVOR), entry(7, STAR, "open", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", false));

        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("entry without approval").contains("#7"));
        assertThat(result.violations()).anySatisfy(v -> assertThat(v).contains("star survivor"));
    }

    // Covers: ADR 0009 (planted: an entry whose mutant is not in the XML is stale)
    @Test
    void anEntryForAMutantThatIsNotThere_isStale() throws Exception {
        Result result = build(run(), entry(9, STAR, "gone", "MathMutator", "Replaced integer addition with subtraction", true));

        assertThat(result.violations()).singleElement().asString().contains("stale entry").contains("#9").contains("not in the XML");
    }

    // Covers: ADR 0009 (planted: an entry whose mutant is now killed is stale)
    @Test
    void anEntryForAMutantThatIsNowKilled_isStale() throws Exception {
        Run r = run(mutation(STAR, "open", "KILLED", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", 77));

        Result result = build(r, entry(1, STAR, "open", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", true));

        assertThat(result.violations()).singleElement().asString().contains("stale entry").contains("now killed");
    }

    // Covers: ADR 0009 (an entry accounts for ONE mutant: two identical survivors need two entries; the line is never matched)
    @Test
    void eachEntry_accountsForOneMutant_andNeverByLine() throws Exception {
        String twice = mutation(STAR, "open", "SURVIVED", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", 10)
                + mutation(STAR, "open", "SURVIVED", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", 99);
        String one = entry(1, STAR, "open", "RemoveConditionalMutator_EQUAL_IF", "removed conditional - replaced equality check with true", true);

        assertThat(build(run(twice), one).violations()).singleElement().asString().contains("star survivor").contains(":99").doesNotContain(":10 ");
        assertThat(build(run(twice), one + entry(2, STAR, "open", "RemoveConditionalMutator_EQUAL_IF",
                "removed conditional - replaced equality check with true", true)).violations()).isEmpty();
    }

    // Covers: ADR 0009 (PIT adds " for class::method" to some descriptions; an entry's description is its prefix; headings with
    // `$` classes and `<init>` or lambda methods parse)
    @Test
    void descriptionsAndHeadings_areMatchedTheWayTheFileWritesThem() throws Exception {
        String nested = "dev.ucplab.core.json.CanonicalJson$Reader";
        Run r = run(mutation(nested, "lambda$new$0", "SURVIVED", "BooleanTrueReturnValsMutator",
                "replaced boolean return with true for dev/ucplab/core/json/CanonicalJson$Reader::lambda$new$0", 3),
                mutation(STAR, "<init>", "SURVIVED", "VoidMethodCallMutator", "removed call to java/util/Objects::requireNonNull", 4));
        String md = entry(1, nested, "lambda$new$0", "BooleanTrueReturnValsMutator", "replaced boolean return with true", true)
                + entry(2, STAR, "<init>", "VoidMethodCallMutator", "removed call to java/util/Objects::requireNonNull", true);

        Result result = MutationReport.build(List.of(r), entries(md), List.of("dev.ucplab.core.json.CanonicalJson", STAR), "c", "p", "q");

        assertThat(entries(md)).extracting(Entry::cls).containsExactly(nested, STAR);
        assertThat(result.violations()).isEmpty();
    }
}
