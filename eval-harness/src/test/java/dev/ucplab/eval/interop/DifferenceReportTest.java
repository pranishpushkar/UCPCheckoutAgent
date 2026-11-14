package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The report's rules on canned inputs (H1 C9): no merchant, no Python. */
class DifferenceReportTest {

    private static final String TABLE = "| id | class | why | status |\n| --- | --- | --- | --- |\n"
            + "| a | theirs | because | n/a |\n| b | ours | a bug | fixed in abc123 |\n| c | ours | a bug | open |\n";

    // Covers: R1, 13.5 (an observed difference that is not in differences.md fails the profile as UNCLASSIFIED)
    @Test
    void anUnclassifiedDifference_fails() {
        var parsed = DifferenceReport.parse(TABLE);

        assertThat(DifferenceReport.violations(Set.of("a", "zzz"), parsed)).anySatisfy(v -> assertThat(v).contains("UNCLASSIFIED").contains("zzz"));
        assertThat(DifferenceReport.violations(Set.of("a"), Map.of("a", parsed.get("a")))).isEmpty();
    }

    // Covers: R1 (an ours entry without a fix or an accepted reason is a failing check; fixed and accepted ones pass)
    @Test
    void anOursWithoutAnAcceptedReason_fails() {
        var parsed = DifferenceReport.parse(TABLE);

        assertThat(DifferenceReport.violations(Set.of(), parsed)).singleElement().asString().contains("OURS").contains("c");
        var accepted = DifferenceReport.parse("| d | ours | x | accepted: harmless |\n| e | weird | x | n/a |\n");
        assertThat(DifferenceReport.violations(Set.of(), accepted)).singleElement().asString().contains("unknown class");
    }

    // Covers: 13.5 (the totals of the report reproduce exactly from the canned rows)
    @Test
    void theTotals_reproduceFromTheCannedInputs() {
        var rows = List.of(new DifferenceReport.RowResult("S0", 20, Map.of("COMPLETED", 20), 20, 0, 5.9, "agrees"),
                new DifferenceReport.RowResult("S6", 20, Map.of("FAILED_NOT_CHARGED", 20), 0, 1, 4.4, "agrees"));
        var parsed = DifferenceReport.parse(TABLE);

        String md = DifferenceReport.markdown(rows, parsed, Set.of("a", "zz"), List.of("probe one"));
        String json = DifferenceReport.json(rows, Set.of("a", "zz"), parsed);

        assertThat(md).contains("Totals: 40 runs, 20 orders, 1 violations.").contains("| zz | UNCLASSIFIED |").contains("- probe one");
        assertThat(json).contains("\"runs\":40").contains("\"orders\":20").contains("\"violations\":1")
                .contains("{\"id\":\"zz\",\"class\":\"UNCLASSIFIED\"}");
    }
}
