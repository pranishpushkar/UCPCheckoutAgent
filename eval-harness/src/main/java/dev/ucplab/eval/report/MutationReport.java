package dev.ucplab.eval.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The H3 mutation report (ADR 0009): reads PIT's {@code mutations.xml} of each run and {@code docs/mutation/equivalents.md},
 * and writes {@code docs/results/h3-mutation-report.md}. It is generated, never written by hand, and it fails (exit status 1,
 * no report) when
 * <ul>
 *   <li>a star class has a survivor or a no-coverage mutant that no approved entry accounts for;
 *   <li>an entry has no {@code approved:} line;
 *   <li>an entry is stale: no surviving mutant of the runs matches it (it is gone, or now killed).
 * </ul>
 * An entry is matched by class, method, mutator and description, never by line; each entry accounts for one mutant. JDK only,
 * so the last job of the workflow can run it without PIT.
 */
public final class MutationReport {

    private MutationReport() {
    }

    /** One PIT mutant. */
    public record Mutant(String cls, String method, String mutator, String status, String description, int line) {
        boolean open() {
            return status.equals("SURVIVED") || status.equals("NO_COVERAGE");
        }

        /** PIT's description without the " for class::method" tail some mutators add. */
        String core() {
            return description.replaceAll(" for [\\w/$.]+::[\\w$<>]+$", "");
        }

        String show() {
            return cls + "." + method + ":" + line + " " + mutator + " " + status + " (" + core() + ")";
        }
    }

    /** One run: its settings (run.properties) and its mutants. */
    public record Run(String name, Map<String, String> settings, List<Mutant> mutants) {
        String get(String key) {
            return settings.getOrDefault(key, "");
        }
    }

    /** One block of equivalents.md. */
    public record Entry(int number, String cls, String method, String mutator, String description, boolean approved) {
        String show() {
            return "#" + number + " " + cls + "." + method + " " + mutator;
        }
    }

    /** The report text and what is wrong. The report is not to be written when {@code violations} is not empty. */
    public record Result(String markdown, List<String> violations) {
    }

    // ------------------------------------------------------------------ reading

    public static List<Mutant> readXml(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList nodes = f.newDocumentBuilder().parse(in).getElementsByTagName("mutation");
        List<Mutant> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element m = (Element) nodes.item(i);
            String mutator = text(m, "mutator");
            out.add(new Mutant(text(m, "mutatedClass"), text(m, "mutatedMethod"),
                    mutator.substring(mutator.lastIndexOf('.') + 1), m.getAttribute("status"), text(m, "description"),
                    Integer.parseInt(text(m, "lineNumber"))));
        }
        return out;
    }

    private static String text(Element m, String tag) {
        NodeList n = m.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent();
    }

    /** Every subdirectory of {@code dir} with a mutations.xml; run.properties is read when present. Sorted by name. */
    public static List<Run> readRuns(Path dir) throws Exception {
        List<Run> runs = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                Path xml = p.resolve("mutations.xml");
                if (!Files.isRegularFile(xml)) {
                    continue;
                }
                Properties props = new Properties();
                Path pp = p.resolve("run.properties");
                if (Files.isRegularFile(pp)) {
                    try (InputStream in = Files.newInputStream(pp)) {
                        props.load(in);
                    }
                }
                Map<String, String> settings = new LinkedHashMap<>();
                props.stringPropertyNames().stream().sorted().forEach(k -> settings.put(k, props.getProperty(k)));
                try (InputStream in = Files.newInputStream(xml)) {
                    runs.add(new Run(p.getFileName().toString(), settings, readXml(in)));
                }
            }
        }
        return runs;
    }

    private static final Pattern HEADING = Pattern.compile("^### (\\d+)\\. (\\S+) (\\w+)(?: \\(.*\\))?\\s*$");

    public static List<Entry> readEntries(String markdown) {
        List<Entry> out = new ArrayList<>();
        String[] lines = markdown.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher h = HEADING.matcher(lines[i]);
            if (!h.matches()) {
                continue;
            }
            String path = h.group(2);
            int dot = path.lastIndexOf('.');
            String description = "";
            boolean approved = false;
            for (int j = i + 1; j < lines.length && !lines[j].startsWith("### "); j++) {
                if (lines[j].startsWith("- description: ")) {
                    description = lines[j].substring("- description: ".length()).trim();
                } else if (lines[j].startsWith("- approved: owner ")) {
                    approved = true;
                }
            }
            out.add(new Entry(Integer.parseInt(h.group(1)), path.substring(0, dot), path.substring(dot + 1), h.group(3),
                    description, approved));
        }
        return out;
    }

    /** Star classes: a name per line, matching the class and its nested classes; blank lines and # comments ignored. */
    public static List<String> readStars(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    // ------------------------------------------------------------------ building

    private static boolean star(List<String> stars, String cls) {
        return stars.stream().anyMatch(s -> cls.equals(s) || cls.startsWith(s + "$"));
    }

    private static boolean accounts(Entry e, Mutant m) {
        return e.cls().equals(m.cls()) && e.method().equals(m.method()) && e.mutator().equals(m.mutator())
                && e.description().startsWith(m.core());
    }

    /** Builds the report and the list of what is wrong with the inputs. */
    public static Result build(List<Run> runs, List<Entry> entries, List<String> stars, String commit, String pitVersion,
            String pluginVersion) {
        List<String> violations = new ArrayList<>();
        List<Mutant> all = runs.stream().flatMap(r -> r.mutants().stream()).toList();
        List<Mutant> open = new ArrayList<>(all.stream().filter(Mutant::open).toList());
        Map<String, Integer> approvedPerClass = new TreeMap<>();
        for (Entry e : entries) {
            if (!e.approved()) {
                violations.add("entry without approval: " + e.show() + " has no `approved:` line");
            }
            Mutant hit = open.stream().filter(m -> accounts(e, m)).findFirst().orElse(null);
            if (hit == null) {
                boolean killed = all.stream().anyMatch(m -> !m.open() && accounts(e, m));
                violations.add("stale entry: " + e.show() + " matches no surviving mutant ("
                        + (killed ? "the mutant is now killed" : "the mutant is not in the XML") + ")");
                continue;
            }
            open.remove(hit);
            if (e.approved()) {
                approvedPerClass.merge(hit.cls(), 1, Integer::sum);
            } else {
                open.add(hit);
            }
        }
        for (Mutant m : open) {
            if (star(stars, m.cls())) {
                violations.add("star survivor: " + m.show() + " has no approved entry");
            }
        }
        for (Mutant m : all) {
            if (star(stars, m.cls()) && !List.of("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT", "MEMORY_ERROR", "NON_VIABLE")
                    .contains(m.status())) {
                violations.add("star mutant with status " + m.status() + ": " + m.show());
            }
        }
        return new Result(markdown(runs, all, stars, approvedPerClass, commit, pitVersion, pluginVersion), violations);
    }

    private static String markdown(List<Run> runs, List<Mutant> all, List<String> stars, Map<String, Integer> approved,
            String commit, String pit, String plugin) {
        StringBuilder sb = new StringBuilder("# H3 mutation report\n\n");
        sb.append("Generated by `MutationReport` from PIT's `mutations.xml` and `docs/mutation/equivalents.md` (ADR 0009). ")
                .append("Do not edit by hand.\n\n");
        sb.append("- Commit: `").append(commit).append("`\n");
        sb.append("- PIT ").append(pit).append(", pitest-junit5-plugin ").append(plugin).append("\n\n");
        sb.append("## Runs\n\n| run | module | classes | mutators | property tries | seed | wall time | mutants |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Run r : runs) {
            sb.append("| ").append(r.name()).append(" | ").append(r.get("module")).append(" | `").append(r.get("classes"))
                    .append("` | ").append(r.get("mutators")).append(" | ").append(r.get("tries")).append(" | ")
                    .append(r.get("seed")).append(" | ").append(r.get("wall.seconds")).append(" s | ")
                    .append(r.mutants().size()).append(" |\n");
        }
        sb.append("\n## Classes\n\n★ marks a path to `complete` or to an approval (ADR 0009): there, every survivor and every no-coverage ")
                .append("mutant is an approved entry of `docs/mutation/equivalents.md`. Timed out counts as killed.\n\n");
        sb.append("| class | ★ | mutants | killed | timed out | survived | no coverage | approved equivalents |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Map<String, List<Mutant>> byClass = new TreeMap<>();
        all.forEach(m -> byClass.computeIfAbsent(m.cls(), k -> new ArrayList<>()).add(m));
        int[] total = new int[6];
        for (Map.Entry<String, List<Mutant>> e : byClass.entrySet()) {
            List<Mutant> ms = e.getValue();
            int[] c = {ms.size(), count(ms, "KILLED"), count(ms, "TIMED_OUT") + count(ms, "MEMORY_ERROR"),
                count(ms, "SURVIVED"), count(ms, "NO_COVERAGE"), approved.getOrDefault(e.getKey(), 0)};
            for (int i = 0; i < 6; i++) {
                total[i] += c[i];
            }
            sb.append("| ").append(e.getKey().replace("dev.ucplab.", "")).append(" | ").append(star(stars, e.getKey()) ? "★" : "")
                    .append(" | ").append(c[0]).append(" | ").append(c[1]).append(" | ").append(c[2]).append(" | ").append(c[3])
                    .append(" | ").append(c[4]).append(" | ").append(c[5]).append(" |\n");
        }
        sb.append("| **total** | | ").append(total[0]).append(" | ").append(total[1]).append(" | ").append(total[2]).append(" | ")
                .append(total[3]).append(" | ").append(total[4]).append(" | ").append(total[5]).append(" |\n");
        long starMutants = all.stream().filter(m -> star(stars, m.cls())).count();
        long starOpen = all.stream().filter(m -> star(stars, m.cls()) && m.open()).count();
        sb.append("\nStar classes: ").append(starMutants).append(" mutants, ").append(starOpen)
                .append(" survivors or no-coverage, all of them approved equivalents.\n");
        return sb.toString();
    }

    private static int count(Collection<Mutant> ms, String status) {
        return (int) ms.stream().filter(m -> m.status().equals(status)).count();
    }

    // ------------------------------------------------------------------ main

    /**
     * {@code MutationReport <runsDir> <equivalents.md> <stars.txt> <out.md> <commit> <pitVersion> <pluginVersion>}. Prints
     * every violation and exits 1 without writing the report when there is one.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            System.err.println("usage: MutationReport <runsDir> <equivalents.md> <stars.txt> <out.md> <commit> <pit> <plugin>");
            System.exit(2);
        }
        Result r = build(readRuns(Path.of(args[0])), readEntries(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)),
                readStars(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)), args[4], args[5], args[6]);
        if (!r.violations().isEmpty()) {
            r.violations().stream().sorted(Comparator.naturalOrder()).forEach(v -> System.err.println("FAIL " + v));
            System.exit(1);
        }
        Files.writeString(Path.of(args[3]), r.markdown(), StandardCharsets.UTF_8);
        System.out.println("wrote " + args[3]);
    }
}
