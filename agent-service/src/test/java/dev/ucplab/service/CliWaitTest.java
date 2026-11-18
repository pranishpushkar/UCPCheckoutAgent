package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.service.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code ucplab wait} against a fake of the API's sign-in and intent routes, so each state can be answered without a
 * merchant: wait returns on every terminal state of 7.1 and only on those.
 */
@Tag("sequential")
class CliWaitTest {

    private static final String INTENT = "int_wait1";

    private final AtomicReference<String> state = new AtomicReference<>("QUOTING");
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startFake() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/auth/dev-login", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Set-Cookie", "ucplab_session=fake-session; Path=/; HttpOnly");
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(303, -1);
            ex.close();
        });
        server.createContext("/api/me", ex -> answer(ex, "{\"email\":\"alice@example.com\"}", "fake-csrf"));
        server.createContext("/api/intents/" + INTENT,
                ex -> answer(ex, "{\"id\":\"" + INTENT + "\",\"state\":\"" + state.get() + "\"}", null));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopFake() {
        server.stop(0);
    }

    private static void answer(com.sun.net.httpserver.HttpExchange ex, String json, String csrf) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (csrf != null) {
            ex.getResponseHeaders().add("X-CSRF-Token", csrf);
        }
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private record Result(int exit, String out, String err) {
    }

    private Result waitFor(String timeout) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[] {"--user", "alice", "--base-url", baseUrl, "wait", INTENT, "--timeout", timeout},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                s -> { });
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // Covers: 7.1, 23.2, H5 C7c (HANDED_OFF is terminal: wait returns 0 on a handed-off intent instead of timing out)
    @Test
    void wait_returnsOnHandedOff() {
        state.set(IntentState.HANDED_OFF.name());

        Result r = waitFor("5s");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("intent " + INTENT + " HANDED_OFF");
    }

    // Covers: 7.1 (wait returns on every terminal state, and times out, exit 3, on one that is not)
    @Test
    void wait_returnsOnEveryTerminalState_andOnlyOnThose() {
        for (IntentState s : IntentState.values()) {
            state.set(s.name());

            Result r = waitFor(s.isTerminal() ? "5s" : "1s");

            assertThat(r.exit()).as(s + ": " + r.err()).isEqualTo(s.isTerminal() ? 0 : 3);
        }
        assertThat(Arrays.stream(IntentState.values()).filter(IntentState::isTerminal)).contains(IntentState.HANDED_OFF);
    }
}
