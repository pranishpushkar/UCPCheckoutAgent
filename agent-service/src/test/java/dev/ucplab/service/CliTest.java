package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.service.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line client (m6-plan section 8, FR3, FR5, C19) against the running service: a purchase from the first
 * message to COMPLETED, nothing it prints holding a secret, another user's intent being "not found" (T6), the Origin it
 * sends, and that it starts from the two {@code target/classes} folders alone, with no Spring.
 */
class CliTest {

    @TempDir
    Path dir;

    private TestService service;
    private String baseUrl;
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final StringBuilder everything = new StringBuilder();

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private void start() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        service = TestService.start(dir.resolve("service"), Map.of("PUBLIC_ORIGIN", baseUrl), Optional.empty(),
                List.of(), Optional.empty(), Map.of(), port);
    }

    private record Result(int exit, String out, String err) {}

    private Result cli(String user, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> all = new ArrayList<>(List.of("--user", user, "--base-url", baseUrl));
        all.addAll(List.of(args));
        int exit = Cli.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), secrets::add);
        Result r = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        everything.append(r.out()).append(r.err());
        return r;
    }

    private static String match(String pattern, String text) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        assertThat(m.find()).as(pattern + " in:\n" + text).isTrue();
        return m.group(1);
    }

    /** alice has an intent awaiting approval, through the CLI; returns its id. */
    private String proposeAsAlice() {
        String run = match("run (\\S+) waiting_for_user", cli("alice", "run", "hi").out());
        Result answered = cli("alice", "answer", run, "a blue mug please");
        assertThat(answered.exit()).isZero();
        return match("(?m)^  intent (\\S+)$", answered.out());
    }

    // Covers: FR3, FR5, 23.1, 23.2, 23.3 (a run, an answer, the approval card, approve and wait gives COMPLETED with one
    // order at the merchant; the read commands and the lab commands work; every invocation signed out)
    @Test
    void runApproveWait_givesCompleted() throws Exception {
        start();
        Result first = cli("alice", "run", "hi");
        assertThat(first.exit()).isZero();
        assertThat(first.out()).contains("waiting_for_user").contains("What would you like to buy?");
        String run = match("run (\\S+) waiting_for_user", first.out());
        Result answered = cli("alice", "answer", run, "a blue mug please");
        assertThat(answered.out()).contains("waiting_for_approval");
        String intent = match("(?m)^  intent (\\S+)$", answered.out());

        Result card = cli("alice", "approval", intent);
        assertThat(card.exit()).isZero();
        assertThat(card.out()).contains("total $").contains("other charges (tax)").contains("price and stock are not held")
                .contains("expires ");
        assertThat(cli("alice", "run-status", run).out()).contains("tool search_catalog: ok");
        assertThat(cli("alice", "intents", "--state", "AWAITING_APPROVAL").out()).contains(intent);
        assertThat(cli("alice", "intent", intent).out()).contains("AWAITING_APPROVAL").contains("merchant ");

        Result approved = cli("alice", "approve", intent);
        assertThat(approved.exit()).isZero();
        Result waited = cli("alice", "wait", intent, "--timeout", "60s");

        assertThat(waited.exit()).isZero();
        assertThat(waited.out()).contains("COMPLETED");
        assertThat(service.merchant.orders()).hasSize(1);
        assertThat(cli("alice", "events", intent).out()).contains("transition");
        assertThat(cli("alice", "lab", "orders").out()).contains("orders 1");
        assertThat(cli("alice", "lab", "faults", "drop_next_complete").out()).contains("fault set");
        assertThat(cli("alice", "lab", "faults", "clear").out()).contains("faults cleared");
        assertThat(cli("alice", "lab", "reset").out()).contains("lab reset");
        assertThat(service.merchant.orders()).isEmpty();
        // Every invocation signed out: no session is left behind.
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("service")
                .resolve("service.db")); java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM user_session")) {
            assertThat(rs.next() && rs.getInt(1) == 0).as("sessions left after the CLI's sign-outs").isTrue();
        }
    }

    // Covers: I8, 18.5, 23.2 (nothing the CLI prints holds the session cookie, the CSRF token or the approval token,
    // through success, a reject, a refusal and a usage error)
    @Test
    void outputs_holdNoSecret() throws Exception {
        start();
        String intent = proposeAsAlice();
        cli("alice", "approval", intent);
        cli("alice", "reject", intent);
        cli("alice", "approve", intent);
        cli("bob", "approval", intent);
        cli("alice", "no-such-command");
        cli("alice", "intent", "not an id");

        assertThat(secrets).as("secrets the CLI handled").hasSizeGreaterThanOrEqualTo(6);
        for (String secret : secrets) {
            assertThat(everything.toString()).doesNotContain(secret);
        }
        assertThat(everything.toString().toLowerCase()).doesNotContain("ucplab_session").doesNotContain("set-cookie")
                .doesNotContain("x-csrf-token");
    }

    // Covers: T6, 23.2 (bob cannot read, approve or see the card of alice's intent: "not found", a non-zero exit, and
    // nothing of alice's on stdout)
    @Test
    void bob_cannotSeeAlicesIntent_isNotFound() throws Exception {
        start();
        String intent = proposeAsAlice();

        for (String command : List.of("intent", "events", "approval", "approve", "reject")) {
            Result r = cli("bob", command, intent);
            assertThat(r.exit()).as(command).isNotZero();
            assertThat(r.err()).as(command).contains("not found");
            assertThat(r.out()).as(command).isEmpty();
        }
        assertThat(cli("bob", "intents").out()).doesNotContain(intent);
        assertThat(cli("alice", "intent", intent).out()).contains("AWAITING_APPROVAL");
    }

    // Covers: 18.5 (the CLI sends Origin equal to --base-url: a base URL the service does not list is refused at sign-in,
    // and one it lists works)
    @Test
    void origin_isTheBaseUrl() throws Exception {
        start();
        String listed = baseUrl;
        baseUrl = baseUrl.replace("127.0.0.1", "localhost");

        Result refused = cli("alice", "lab", "orders");
        baseUrl = listed;
        Result accepted = cli("alice", "lab", "orders");

        assertThat(refused.exit()).isNotZero();
        assertThat(refused.err()).contains("sign-in refused (403)");
        assertThat(accepted.exit()).isZero();
    }

    // Covers: 8 (the CLI starts from agent-service/target/classes and agent-core/target/classes alone, with no Spring and no
    // other jar, runs a command against the service, and leaves nothing on disk)
    @Test
    void startsFromTheTwoClassesFoldersAlone_andWritesNothing() throws Exception {
        start();
        Path here = Path.of("").toAbsolutePath();
        Path service = here.resolve("target/classes");
        Path core = here.resolve("../agent-core/target/classes").normalize();
        assertThat(service.resolve("dev/ucplab/service/cli/Cli.class")).exists();
        assertThat(core.resolve("dev/ucplab/core/json/LenientJson.class")).exists();
        Path work = Files.createDirectory(dir.resolve("work"));

        Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp",
                service + java.io.File.pathSeparator + core, "dev.ucplab.service.cli.Cli", "--user", "alice",
                "--base-url", baseUrl, "run", "hi").directory(work.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        assertThat(output).contains("waiting_for_user");
        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.toList()).as("files the CLI wrote").isEmpty();
        }
    }
}
