package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.EvidenceWriter;
import dev.ucplab.eval.report.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * m4-plan risk 9, I8: a full report from real runs of every scenario, with an evidence folder for every run, never
 * contains a payment token, an approval token, an Authorization value, the bodyHmac key (hex or Base64), or any
 * request or response body the agent stored. The test sees the real tokens, key and bodies and searches every file
 * written for each of them, the stdout and stderr of every agent child process (S9, S28) included.
 *
 * <p>M5 (m5-plan C14): the evaluation's canary ({@link Canary}) is every run's session id and part of the approval
 * secret, and must appear nowhere either: not in a report, an evidence file ({@code run.log}, {@code trace.jsonl},
 * {@code run.json} and the {@code intents.json} payloads included) or this JVM's stdout and stderr during the runs. A
 * positive control first shows that the search finds a planted canary in a file, in a run's log and in the captured
 * output.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedactionTest {

    // Covers: I8 (m5-plan C14 positive control: the same search finds the canary wherever it is planted)
    @Test
    @Order(1)
    void positiveControl_theSearchFindsAPlantedCanary(@TempDir Path out) throws IOException {
        String canary = Canary.generate();
        assertThat(canary).matches(Canary.FORMAT);
        Map<String, String> forbidden = Map.of(canary, "the canary");
        Files.createDirectories(out.resolve("evidence/X-1"));
        Files.writeString(out.resolve("report.md"), "clean");
        assertThat(leaks(out, forbidden, "")).isEmpty();

        Files.writeString(out.resolve("evidence/X-1/trace.jsonl"), "{\"session\":\"" + canary + "\"}\n");
        assertThat(leaks(out, forbidden, "")).containsExactly("the canary in evidence/X-1/trace.jsonl");

        String logged;
        try (RunLog runLog = RunLog.open()) {
            Logger.getLogger(RedactionTest.class.getName()).warning("session " + canary);
            logged = runLog.text();
        }
        assertThat(logged).contains(canary);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(new Tee(stdout, captured), true, StandardCharsets.UTF_8));
        try {
            System.out.println("session " + canary);
        } finally {
            System.setOut(stdout);
        }
        assertThat(leaks(out.resolve("no-files"), forbidden, captured.toString(StandardCharsets.UTF_8)))
                .containsExactly("the canary in stdout/stderr");
    }

    // Covers: I8, 13.5, S9, S28, S10 (m4-plan risk 9 and m5-plan C14: nothing secret, and not the canary, reaches the
    // report, the evidence folders, the agent child processes' logs kept in them, or this JVM's output)
    @Test
    @Order(2)
    void fullReportFromRealRuns_containsNoSecretOrBody(@TempDir Path out) throws IOException, InterruptedException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String canary = Canary.generate();
        ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<RunEvidence> evidence = new ConcurrentLinkedQueue<>();
        Evaluation eval = new Evaluation(new EvalConfig(2, TimeScale.of(4), 2, 7), Scenarios.all(),
                OptionalInt.empty(), out, Evaluation.EvidenceMode.ALL, tokens::add, evidence::add, key, canary);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        Report report;
        System.setOut(new PrintStream(new Tee(stdout, captured), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(stderr, captured), true, StandardCharsets.UTF_8));
        try {
            report = eval.run(new CommitInfo("0123456789abcdef0123456789abcdef01234567", "test", false, "git"));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }

        Map<String, String> forbidden = new java.util.LinkedHashMap<>();
        forbidden.put("tok_", "a payment token");
        forbidden.put("Authorization", "an Authorization header");
        forbidden.put("Bearer", "an Authorization value");
        forbidden.put("\"credential\"", "a payment credential");
        forbidden.put(HexFormat.of().formatHex(key), "the bodyHmac key (hex)");
        forbidden.put(HexFormat.of().withUpperCase().formatHex(key), "the bodyHmac key (HEX)");
        forbidden.put(Base64.getEncoder().withoutPadding().encodeToString(key), "the bodyHmac key (Base64)");
        forbidden.put(Base64.getUrlEncoder().withoutPadding().encodeToString(key), "the bodyHmac key (Base64url)");
        forbidden.put(canary, "the canary");
        tokens.forEach(t -> forbidden.put(t, "an approval token"));
        Set<String> bodies = new LinkedHashSet<>();
        for (RunEvidence e : evidence) {
            for (RunEvidence.Persisted p : e.intents()) {
                for (MerchantAttempt a : p.attempts()) {
                    a.requestBody().ifPresent(b -> bodies.add(b.json()));
                    a.responseJson().filter(r -> r.length() > 20).ifPresent(bodies::add);
                }
            }
        }
        bodies.forEach(b -> forbidden.put(b, "a stored request or response body"));

        // The search is meaningful only if the runs really handled tokens and bodies.
        assertThat(tokens).as("approval tokens seen").isNotEmpty();
        assertThat(bodies).as("bodies stored by the agent").anySatisfy(b -> assertThat(b).contains("tok_"));
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(out)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        assertThat(files).contains(out.resolve("report.json"), out.resolve("report.md"));
        assertThat(files).anySatisfy(f -> assertThat(f.toString()).contains("intents.json"));
        assertThat(files.size()).isGreaterThan(2 + 6 * report.scenarios().size());
        // Every child log the runs produced was written out, so the search below covers it.
        long childLogs = evidence.stream().mapToLong(e -> e.children().size()).sum();
        assertThat(childLogs).as("agent child processes started").isGreaterThanOrEqualTo(8);
        assertThat(evidence).allSatisfy(e -> assertThat(e.children()).allSatisfy(
                c -> assertThat(c.log()).contains(AgentMain.READY)));
        assertThat(files.stream().filter(f -> f.getFileName().toString().matches("child-\\d+\\.log")).count())
                .as("child logs in the evidence folders").isEqualTo(childLogs);
        // Every run's folder has its run.log; every agent run's folder has its run record and trace.
        assertThat(evidence).hasSize(files.stream()
                .filter(f -> f.getFileName().toString().equals(EvidenceWriter.RUN_LOG)).toList().size());
        long agentRuns = evidence.stream().filter(e -> e.agentRun().isPresent()).count();
        assertThat(agentRuns).as("agent runs").isGreaterThanOrEqualTo(2L * Scenarios.agent().size());
        for (String name : List.of(EvidenceWriter.RUN_FILE, EvidenceWriter.TRACE_FILE)) {
            assertThat(files.stream().filter(f -> f.getFileName().toString().equals(name)).count())
                    .as(name).isEqualTo(agentRuns);
        }
        assertThat(leaks(out, forbidden, captured.toString(StandardCharsets.UTF_8))).isEmpty();
    }

    /** Each forbidden string found, as "what in file", searching every file under {@code dir} and {@code output}. */
    private static List<String> leaks(Path dir, Map<String, String> forbidden, String output) throws IOException {
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                forbidden.forEach((needle, what) -> {
                    if (text.contains(needle)) {
                        found.add(what + " in " + dir.relativize(f).toString().replace('\\', '/'));
                    }
                });
            }
        }
        forbidden.forEach((needle, what) -> {
            if (output.contains(needle)) {
                found.add(what + " in stdout/stderr");
            }
        });
        return found;
    }

    /** Writes to the real stream and keeps a copy for the search. */
    private static final class Tee extends OutputStream {
        private final OutputStream real;
        private final ByteArrayOutputStream copy;

        Tee(OutputStream real, ByteArrayOutputStream copy) {
            this.real = real;
            this.copy = copy;
        }

        @Override
        public void write(int b) throws IOException {
            real.write(b);
            synchronized (copy) {
                copy.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            real.write(b, off, len);
            synchronized (copy) {
                copy.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            real.flush();
        }
    }
}
