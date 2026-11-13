package dev.ucplab.adk.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.adk.gemini.GeminiException.Reason;
import dev.ucplab.core.agent.Conversation;
import dev.ucplab.core.agent.Message;
import dev.ucplab.core.agent.ModelClientSettings;
import dev.ucplab.core.agent.ModelKey;
import dev.ucplab.core.agent.ToolSpec;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * I8, m5-plan risk 2 (C15): a canary planted as the {@link ModelKey} appears nowhere when the Gemini call fails, in
 * each way a stub can make it fail: 401 and 500 with error bodies that repeat the key, a dropped connection, and a
 * timeout. Searched: every log record through {@code java.util.logging} (the SDK's and OkHttp's only logging path; the
 * genai 1.80.0 sources import no SLF4J, and the agent-adk tree has no SLF4J binding) at the finest level, this JVM's
 * stdout and stderr, and each exception's message, toString, stack trace (causes and suppressed included), the client's
 * and the key's toString. A positive control first shows that the search finds a planted canary in each place.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeminiRedactionTest {

    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("ask_user", "Ask the user.",
            "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}}}"));
    private static final List<String> LOGGERS = List.of("", "com.google.genai", "okhttp3", "com.google.auth");

    private final String canary = canary();
    private final StringBuffer logged = new StringBuffer();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<String> seen = new ArrayList<>();
    private final List<Level> levels = new ArrayList<>();
    private Handler handler;
    private PrintStream stdout;
    private PrintStream stderr;

    private static String canary() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return "canary-" + HexFormat.of().formatHex(b);
    }

    @BeforeEach
    void capture() {
        SimpleFormatter format = new SimpleFormatter();
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.append(format.format(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        for (String name : LOGGERS) {
            Logger logger = Logger.getLogger(name);
            levels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
        }
        Logger.getLogger("").addHandler(handler);
        stdout = System.out;
        stderr = System.err;
        System.setOut(new PrintStream(new Tee(stdout, output), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(stderr, output), true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(stdout);
        System.setErr(stderr);
        Logger.getLogger("").removeHandler(handler);
        for (int i = 0; i < LOGGERS.size(); i++) {
            Logger.getLogger(LOGGERS.get(i)).setLevel(levels.get(i));
        }
    }

    /** Everything an exception can show: message, toString, the whole stack trace with causes and suppressed. */
    private void see(Throwable e) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        seen.add(String.valueOf(e.getMessage()));
        seen.add(String.valueOf(e.getLocalizedMessage()));
        seen.add(e.toString());
        seen.add(trace.toString());
        for (Throwable s : e.getSuppressed()) {
            see(s);
        }
        if (e.getCause() != null) {
            see(e.getCause());
        }
    }

    /** Where the canary was found: "log", "output" or "exception". */
    private List<String> found() {
        List<String> where = new ArrayList<>();
        if (logged.toString().contains(canary)) {
            where.add("log");
        }
        if (output.toString(StandardCharsets.UTF_8).contains(canary)) {
            where.add("output");
        }
        if (seen.stream().anyMatch(s -> s.contains(canary))) {
            where.add("exception");
        }
        return where;
    }

    private GeminiModelClient client(URI url, Duration timeout) {
        GeminiModelClient client = new GeminiModelClient(ModelKey.of(canary), ModelClientSettings.defaults()
                .withModel("stub-model").withBaseUrl(url).withCallTimeout(timeout), name -> null);
        seen.add(client.toString());
        return client;
    }

    private void fails(GeminiModelClient client, Reason reason) {
        Conversation c = new Conversation("p", List.of(new Message.User("hi")));
        assertThatThrownBy(() -> client.generate(c, TOOLS)).isInstanceOfSatisfying(GeminiException.class, e -> {
            see(e);
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).isEmpty();
        });
        client.close();
    }

    // Covers: I8 (positive control: the same search finds a canary planted in a log record at the finest level, in
    // stdout, and in an exception's cause)
    @Test
    @Order(1)
    void positiveControl_theSearchFindsAPlantedCanary() {
        assertThat(found()).isEmpty();

        Logger.getLogger("com.google.genai.ApiClient").finest("planted " + canary);
        System.out.println("planted " + canary);
        RuntimeException planted = new RuntimeException("outer");
        planted.addSuppressed(new IllegalStateException("planted " + canary));
        see(planted);

        assertThat(found()).containsExactly("log", "output", "exception");
    }

    // Covers: I8 (401 and 500 whose bodies repeat the key, a dropped connection and a timeout: the canary appears in no
    // log record, no output and no exception)
    @Test
    @Order(2)
    void everyFailure_canaryAppearsNowhere() throws Exception {
        String echo = "{\"error\":{\"code\":%d,\"message\":\"API key $KEY not valid\",\"status\":\"X\"}}";
        try (GeminiStub stub = new GeminiStub().echoingKey()) {
            stub.answer(401, echo.formatted(401));
            fails(client(stub.url(), Duration.ofSeconds(5)), Reason.UNAUTHORIZED);
            stub.answer(500, echo.formatted(500));
            fails(client(stub.url(), Duration.ofSeconds(5)), Reason.SERVER_ERROR);
            // The stub really did repeat the key: the search below would find it had it leaked.
            assertThat(stub.received()).hasSize(2).allSatisfy(r -> assertThat(r.keyHeader()).isEqualTo(canary));
        }
        try (GeminiStub.Dropping dropping = GeminiStub.dropping()) {
            fails(client(dropping.url(), Duration.ofSeconds(5)), Reason.NETWORK);
        }
        try (GeminiStub held = new GeminiStub().holding()) {
            held.answer(200, "{}");
            long t0 = System.nanoTime();
            fails(client(held.url(), Duration.ofMillis(300)), Reason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
        }
        seen.add(ModelKey.of(canary).toString());

        assertThat(found()).isEmpty();
        // The search ran over real material: each failure left its exception, and the SDK's loggers were at ALL.
        assertThat(seen).hasSizeGreaterThan(16);
        assertThat(Logger.getLogger("com.google.genai").getLevel()).isEqualTo(Level.ALL);
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
        public void write(int b) throws java.io.IOException {
            real.write(b);
            synchronized (copy) {
                copy.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            real.write(b, off, len);
            synchronized (copy) {
                copy.write(b, off, len);
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            real.flush();
        }
    }
}
