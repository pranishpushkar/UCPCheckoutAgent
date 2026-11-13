package dev.ucplab.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The end-of-run scan of a live evaluation (I8, m5-plan section 10 item 4, C16): every file under the output folder,
 * and the evaluation's captured stdout and stderr, are searched for the real key and the evaluation's canary. A hit
 * fails the job and deletes the output folder's contents (report, evidence, logs), so nothing holding a secret is left
 * to upload. A hit is reported by what and where, never by its text.
 */
final class LeakScan {

    private LeakScan() {
    }

    /**
     * Each forbidden string found, as "what in where" ({@code where} a path relative to {@code dir}, or
     * {@code stdout/stderr}).
     *
     * @param forbidden each secret, mapped to how a hit names it ("the model key", "the canary")
     */
    static List<String> scan(Path dir, String output, Map<String, String> forbidden) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(output, "output");
        forbidden.keySet().forEach(k -> {
            if (k.isEmpty()) {
                throw new IllegalArgumentException("an empty forbidden string matches everything");
            }
        });
        List<String> hits = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                // Bytes as ISO-8859-1: any byte sequence reads, and an ASCII secret matches wherever it occurs.
                String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                forbidden.forEach((secret, what) -> {
                    if (text.contains(secret)) {
                        hits.add(what + " in " + dir.relativize(f).toString().replace('\\', '/'));
                    }
                });
            }
        }
        forbidden.forEach((secret, what) -> {
            if (output.contains(secret)) {
                hits.add(what + " in stdout/stderr");
            }
        });
        return hits;
    }

    /**
     * Scans; on any hit, prints each hit (what and where only) to {@code err} and empties {@code dir}.
     *
     * @return whether the scan was clean
     */
    static boolean enforce(Path dir, String output, Map<String, String> forbidden, PrintStream err)
            throws IOException {
        List<String> hits = scan(dir, output, forbidden);
        if (hits.isEmpty()) {
            return true;
        }
        hits.forEach(h -> err.println("leak scan: " + h));
        deleteContents(dir);
        err.println("leak scan: " + hits.size() + " hit(s); the output folder " + dir + " was emptied");
        return false;
    }

    /** Deletes everything under {@code dir}, keeping the folder itself. */
    static void deleteContents(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) {
                    Files.delete(p);
                }
            }
        }
    }
}
