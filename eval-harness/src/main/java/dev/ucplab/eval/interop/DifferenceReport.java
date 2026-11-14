package dev.ucplab.eval.interop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The differential report (H1 C9, C9b): the raw wire differences of {@link WireDiff} are explained by the entries of
 * docs/interop/differences.md, each with a {@code match} glob tested against {@code row/step/kind:detail}. What fails the
 * profile is computed: a wire difference no entry matches (UNCLASSIFIED), an {@code ours} entry without a fix or an accepted
 * reason, and an entry that matches nothing, unless its evidence is {@code probe} or {@code history} (things no wire record shows).
 */
public final class DifferenceReport {

    static final List<String> CLASSES = List.of("ours", "theirs", "choice", "ambiguity");
    static final List<String> UNCOMPUTED = List.of("probe", "history");

    /** One classified difference: a {@code match} (comma-separated globs, empty for probe and history entries). */
    public record Entry(String id, String type, String match, String evidence, String why, String status) {
    }

    /** One row of the report. */
    public record RowResult(String row, int runs, Map<String, Integer> states, int orders, int violations, double seconds,
                            String agreement) {
    }

    /** What an evaluation found: the violations, and for each raw difference the entry that explains it. */
    public record Evaluation(List<String> violations, Map<String, String> explained, Map<String, Integer> perEntry) {
    }

    private DifferenceReport() {
    }

    /** Parses the markdown table of differences.md; lines that are not table rows are ignored. */
    public static Map<String, Entry> parse(String markdown) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String line : markdown.split("\n")) {
            if (!line.startsWith("|")) {
                continue;
            }
            String[] c = line.substring(1).split("\\|", -1);
            if (c.length < 6 || c[0].trim().equals("id") || c[0].trim().startsWith("---")) {
                continue;
            }
            out.put(c[0].trim(), new Entry(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim(), c[4].trim(), c[5].trim()));
        }
        return out;
    }

    static boolean matches(String glob, String text) {
        StringBuilder re = new StringBuilder();
        String[] parts = glob.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            re.append(i == 0 ? "" : ".*").append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(re.toString(), Pattern.DOTALL).matcher(text).matches();
    }

    /** Explains each raw difference by the first entry whose match fits; computes every violation. */
    public static Evaluation evaluate(List<WireDiff.Diff> diffs, Map<String, Entry> entries) {
        List<String> violations = new ArrayList<>();
        Map<String, String> explained = new LinkedHashMap<>();
        Map<String, Integer> perEntry = new LinkedHashMap<>();
        entries.keySet().forEach(k -> perEntry.put(k, 0));
        for (WireDiff.Diff d : diffs) {
            String by = null;
            for (Entry e : entries.values()) {
                if (e.match().isEmpty()) {
                    continue;
                }
                for (String glob : e.match().split(",")) {
                    if (matches(glob.trim(), d.matchText())) {
                        by = e.id();
                        break;
                    }
                }
                if (by != null) {
                    break;
                }
            }
            if (by == null) {
                violations.add("UNCLASSIFIED wire difference: " + d.matchText());
            } else {
                explained.put(d.matchText(), by);
                perEntry.merge(by, 1, Integer::sum);
            }
        }
        for (Entry e : entries.values()) {
            if (!CLASSES.contains(e.type())) {
                violations.add("unknown class '" + e.type() + "' for " + e.id());
            } else if (e.type().equals("ours") && !(e.status().startsWith("fixed in ") || e.status().startsWith("accepted: "))) {
                violations.add("OURS without a fix or an accepted reason: " + e.id());
            }
            if (!UNCOMPUTED.contains(e.evidence()) && perEntry.get(e.id()) == 0) {
                violations.add("the entry " + e.id() + " matches nothing: remove it, or mark it evidence: probe or history");
            }
        }
        return new Evaluation(violations, explained, perEntry);
    }

    /** The markdown report; its totals come only from {@code rows} and {@code diffs}. */
    public static String markdown(List<RowResult> rows, Map<String, Entry> entries, List<WireDiff.Diff> diffs, Evaluation ev,
                                  List<String> findings) {
        StringBuilder b = new StringBuilder("# H1 interop report\n\n");
        b.append("| row | runs | terminal states | orders (sample's own table) | violations | time | agreement with the lab |\n");
        b.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (RowResult r : rows) {
            b.append(String.format("| %s | %d | %s | %d | %d | %.1f s | %s |%n", r.row(), r.runs(), r.states(), r.orders(),
                    r.violations(), r.seconds(), r.agreement()));
        }
        b.append(String.format("%nTotals: %d runs, %d orders, %d violations.%n", rows.stream().mapToInt(RowResult::runs).sum(),
                rows.stream().mapToInt(RowResult::orders).sum(), rows.stream().mapToInt(RowResult::violations).sum()));
        b.append(String.format("%n## Wire differences%n%n%d raw wire differences, %d explained by %d entries, %d unclassified.%n%n",
                diffs.size(), ev.explained().size(), ev.perEntry().values().stream().filter(n -> n > 0).count(),
                diffs.size() - ev.explained().size()));
        b.append("| entry | class | evidence | raw differences it explains | why |\n| --- | --- | --- | --- | --- |\n");
        for (Entry e : entries.values()) {
            b.append(String.format("| %s | %s | %s | %d | %s |%n", e.id(), e.type(), e.evidence(), ev.perEntry().get(e.id()), e.why()));
        }
        b.append("\n### Every raw difference and the entry that explains it\n\n");
        for (WireDiff.Diff d : diffs) {
            b.append("- `").append(d.matchText()).append("` : ").append(ev.explained().getOrDefault(d.matchText(), "UNCLASSIFIED")).append('\n');
        }
        if (!findings.isEmpty()) {
            b.append("\n## Probes\n\n");
            findings.forEach(f -> b.append("- ").append(f).append('\n'));
        }
        return b.toString();
    }

    /** The same figures as JSON, for the report files. */
    public static String json(List<RowResult> rows, List<WireDiff.Diff> diffs, Evaluation ev) {
        StringBuilder b = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            RowResult r = rows.get(i);
            b.append(i == 0 ? "" : ",").append(String.format("{\"row\":\"%s\",\"runs\":%d,\"orders\":%d,\"violations\":%d}",
                    r.row(), r.runs(), r.orders(), r.violations()));
        }
        b.append("],\"runs\":").append(rows.stream().mapToInt(RowResult::runs).sum()).append(",\"orders\":")
                .append(rows.stream().mapToInt(RowResult::orders).sum()).append(",\"violations\":")
                .append(rows.stream().mapToInt(RowResult::violations).sum()).append(",\"wireDifferences\":").append(diffs.size())
                .append(",\"explained\":").append(ev.explained().size()).append(",\"unclassified\":")
                .append(diffs.size() - ev.explained().size()).append('}');
        return b.toString();
    }
}
