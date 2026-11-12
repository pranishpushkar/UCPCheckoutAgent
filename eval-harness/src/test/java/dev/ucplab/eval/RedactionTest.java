package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.eval.report.CommitInfo;
import dev.ucplab.eval.report.Report;
import java.io.IOException;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * m4-plan risk 9, I8: a full report from real runs of every scenario, with an evidence folder for every run, never
 * contains a payment token, an approval token, an Authorization value, the bodyHmac key (hex or Base64), or any
 * request or response body the agent stored. The test sees the real tokens, key and bodies and searches every file
 * written for each of them.
 */
class RedactionTest {

    // Covers: I8, 13.5 (m4-plan risk 9: nothing secret reaches the report or the evidence folders)
    @Test
    void fullReportFromRealRuns_containsNoSecretOrBody(@TempDir Path out) throws IOException, InterruptedException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<RunEvidence> evidence = new ConcurrentLinkedQueue<>();
        Evaluation eval = new Evaluation(new EvalConfig(2, TimeScale.of(4), 2, 7), Scenarios.all(),
                OptionalInt.empty(), out, Evaluation.EvidenceMode.ALL, tokens::add, evidence::add, key);

        Report report = eval.run(new CommitInfo("0123456789abcdef0123456789abcdef01234567", "test", false, "git"));

        Map<String, String> forbidden = new java.util.LinkedHashMap<>();
        forbidden.put("tok_", "a payment token");
        forbidden.put("Authorization", "an Authorization header");
        forbidden.put("Bearer", "an Authorization value");
        forbidden.put("\"credential\"", "a payment credential");
        forbidden.put(HexFormat.of().formatHex(key), "the bodyHmac key (hex)");
        forbidden.put(HexFormat.of().withUpperCase().formatHex(key), "the bodyHmac key (HEX)");
        forbidden.put(Base64.getEncoder().withoutPadding().encodeToString(key), "the bodyHmac key (Base64)");
        forbidden.put(Base64.getUrlEncoder().withoutPadding().encodeToString(key), "the bodyHmac key (Base64url)");
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
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            forbidden.forEach((needle, what) -> assertThat(text)
                    .as("%s must not appear in %s", what, out.relativize(f)).doesNotContain(needle));
        }
    }
}
