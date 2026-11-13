package dev.ucplab.faultproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultProxyMainTest {

    // Covers: §12 (the CLI flags for the scripts)
    @Test
    void parse_readsTheFlags() {
        FaultProxyMain.Args a = FaultProxyMain.Args.parse(new String[] {
                "--upstream", "http://127.0.0.1:8080", "--port", "0", "--rules", "rules.json"});

        assertThat(a.port()).isZero();
        assertThat(a.upstream()).isEqualTo(URI.create("http://127.0.0.1:8080"));
        assertThat(a.rules()).contains(Path.of("rules.json"));
        assertThat(FaultProxyMain.Args.parse(new String[] {"--port", "9", "--upstream", "http://localhost:1"}).rules())
                .isEqualTo(Optional.empty());
    }

    // Covers: §12 (a bad command line is refused, never half-applied)
    @Test
    void parse_refusesABadCommandLine() {
        for (String[] bad : new String[][] {
                {}, {"--port", "0"}, {"--upstream", "http://127.0.0.1:1"}, {"--port"},
                {"--port", "x", "--upstream", "http://127.0.0.1:1"},
                {"--port", "70000", "--upstream", "http://127.0.0.1:1"},
                {"--port", "0", "--port", "1", "--upstream", "http://127.0.0.1:1"},
                {"--port", "0", "--upstream", "http://127.0.0.1:1", "--seed", "4"}}) {
            assertThatThrownBy(() -> FaultProxyMain.Args.parse(bad)).as(String.join(" ", bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // Covers: §12 Body hash (the standalone key is 32 bytes from SecureRandom: two start-ups never share a key)
    @Test
    void newKey_isFreshEachTime() {
        byte[] a = FaultProxyMain.newKey();
        byte[] b = FaultProxyMain.newKey();

        assertThat(a).hasSize(32);
        assertThat(b).hasSize(32);
        assertThat(a).isNotEqualTo(b);
    }

    // Covers: §12 Body hash (the key is never derived from a fixed seed: the source uses SecureRandom and never seeds)
    @Test
    void source_keyComesFromUnseededSecureRandom() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/ucplab/faultproxy/FaultProxyMain.java"));

        assertThat(source).contains("new SecureRandom()").doesNotContain("setSeed").doesNotContain("new Random(")
                .doesNotContain("SplittableRandom").doesNotContain("getInstance(\"SHA1PRNG\"");
    }

    // Covers: §12 (the main starts a working proxy, installs the rules file, and prints only the two addresses)
    @Test
    void start_installsTheRulesFileAndPrintsNoSecret(@TempDir Path dir) throws Exception {
        Path rules = dir.resolve("rules.json");
        Files.writeString(rules, "{\"seed\": 1, \"rules\": [{\"id\": \"no-get\", \"match\": {\"method\": \"GET\"},"
                + " \"action\": {\"type\": \"fail_before_forward\", \"status\": 503}}]}");
        ByteArrayOutputStream printed = new ByteArrayOutputStream();

        try (EchoUpstream upstream = new EchoUpstream();
             FaultProxy proxy = FaultProxyMain.start(new String[] {"--port", "0", "--upstream",
                     upstream.baseUrl().toString(), "--rules", rules.toString()},
                     new PrintStream(printed, true, StandardCharsets.UTF_8));
             HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/x")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(r.statusCode()).isEqualTo(503);
            assertThat(upstream.arrivals()).isEmpty();
            assertThat(proxy.log(0).getFirst().ruleId()).contains("no-get");
            assertThat(printed.toString(StandardCharsets.UTF_8)).isEqualTo("fault-proxy listening on "
                    + proxy.baseUrl() + ", forwarding to " + upstream.baseUrl() + System.lineSeparator());
        }
    }

    // Covers: §12 (an invalid rules file stops the start-up before anything listens)
    @Test
    void start_refusesAnInvalidRulesFile(@TempDir Path dir) throws IOException {
        Path rules = dir.resolve("rules.json");
        Files.writeString(rules, "{\"seed\": 1, \"rules\": [{\"id\": \"x\"}]}");

        assertThatThrownBy(() -> FaultProxyMain.start(new String[] {"--port", "0", "--upstream",
                "http://127.0.0.1:1", "--rules", rules.toString()}, new PrintStream(new ByteArrayOutputStream())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
