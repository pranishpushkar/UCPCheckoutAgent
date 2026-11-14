package dev.ucplab.eval.interop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The differential report (H1 C9): every difference the interop run observed must be classified in
 * docs/interop/differences.md, or the profile fails (UNCLASSIFIED); an {@code ours} entry is a failing check until it is
 * fixed or accepted with a written reason. Pure: the file text and the run's results go in, text and violations come out.
 */
public final class DifferenceReport {

    static final Set<String> CLASSES = Set.of("ours", "theirs", "choice", "ambiguity");

    /** One classified difference. */
    public record Entry(String id, String type, String why, String status) {
    }

    /** One row of the report. */
    public record RowResult(String row, int runs, Map<String, Integer> states, int orders, int violations, double seconds,
                            String agreement) {
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
            if (c.length < 4 || c[0].trim().equals("id") || c[0].trim().startsWith("---")) {
                continue;
            }
            out.put(c[0].trim(), new Entry(c[0].trim(), c[1].trim(), c[2].trim(), c[3].trim()));
        }
        return out;
    }

    /** What fails the profile: an unclassified observed difference, an unknown class, an {@code ours} without a resolution. */
    public static List<String> violations(Set<String> observed, Map<String, Entry> classified) {
        List<String> out = new ArrayList<>();
        for (String id : observed) {
            if (!classified.containsKey(id)) {
                out.add("UNCLASSIFIED difference: " + id);
            }
        }
        for (Entry e : classified.values()) {
            if (!CLASSES.contains(e.type())) {
                out.add("unknown class '" + e.type() + "' for " + e.id());
            } else if (e.type().equals("ours") && !(e.status().startsWith("fixed in ") || e.status().startsWith("accepted: "))) {
                out.add("OURS without a fix or an accepted reason: " + e.id());
            }
        }
        return out;
    }

    /** The markdown report; its totals come only from {@code rows}. */
    public static String markdown(List<RowResult> rows, Map<String, Entry> classified, Set<String> observed, List<String> findings) {
        StringBuilder b = new StringBuilder("# H1 interop report\n\n");
        b.append("| row | runs | terminal states | orders (sample's own table) | violations | time | agreement with the lab |\n");
        b.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (RowResult r : rows) {
            b.append(String.format("| %s | %d | %s | %d | %d | %.1f s | %s |%n", r.row(), r.runs(), r.states(), r.orders(),
                    r.violations(), r.seconds(), r.agreement()));
        }
        b.append(String.format("%nTotals: %d runs, %d orders, %d violations.%n", rows.stream().mapToInt(RowResult::runs).sum(),
                rows.stream().mapToInt(RowResult::orders).sum(), rows.stream().mapToInt(RowResult::violations).sum()));
        b.append("\n## Differences observed\n\n| id | class | status |\n| --- | --- | --- |\n");
        for (String id : observed) {
            Entry e = classified.get(id);
            b.append("| ").append(id).append(" | ").append(e == null ? "UNCLASSIFIED" : e.type()).append(" | ")
                    .append(e == null ? "" : e.status()).append(" |\n");
        }
        if (!findings.isEmpty()) {
            b.append("\n## Probes\n\n");
            findings.forEach(f -> b.append("- ").append(f).append('\n'));
        }
        return b.toString();
    }

    /** The same figures as JSON, for the report files. */
    public static String json(List<RowResult> rows, Set<String> observed, Map<String, Entry> classified) {
        StringBuilder b = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            RowResult r = rows.get(i);
            b.append(i == 0 ? "" : ",").append(String.format("{\"row\":\"%s\",\"runs\":%d,\"orders\":%d,\"violations\":%d}",
                    r.row(), r.runs(), r.orders(), r.violations()));
        }
        b.append("],\"runs\":").append(rows.stream().mapToInt(RowResult::runs).sum()).append(",\"orders\":")
                .append(rows.stream().mapToInt(RowResult::orders).sum()).append(",\"violations\":")
                .append(rows.stream().mapToInt(RowResult::violations).sum()).append(",\"differences\":[");
        int n = 0;
        for (String id : observed) {
            Entry e = classified.get(id);
            b.append(n++ == 0 ? "" : ",").append("{\"id\":\"").append(id).append("\",\"class\":\"")
                    .append(e == null ? "UNCLASSIFIED" : e.type()).append("\"}");
        }
        return b.append("]}").toString();
    }
}
