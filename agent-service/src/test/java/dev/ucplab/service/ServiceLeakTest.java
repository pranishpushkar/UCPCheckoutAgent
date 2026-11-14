package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ucplab.core.agent.ModelClient;
import dev.ucplab.core.agent.ModelTurn;
import dev.ucplab.core.agent.RunId;
import dev.ucplab.core.agent.RunRecord;
import dev.ucplab.core.agent.RunRepository;
import dev.ucplab.core.agent.ToolCall;
import dev.ucplab.core.agent.Usage;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.json.LenientJson;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.LeakEvidence.Allowed;
import dev.ucplab.service.lab.LabControls;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The I8 canary for the running service (m6-plan section 10, C16): a real merchant behind the real fault proxy, the
 * service's logging at its configured levels with the output captured, and a scan of every log line, every response and
 * the SQLite files for each planted canary. Covers: I8, 11, NFR6, 23.2 (the approval token only in the 200 GET body),
 * 18.5 (cookie and CSRF only in the headers that deliver them), 6.2 and 23.1 (message texts in no log and no database).
 */
class ServiceLeakTest {

    private static final Instant T0 = Instant.parse("2026-11-14T09:00:00Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    private LogCapture logs;
    private TestService service;
    private HttpServer stub;
    private final LeakEvidence evidence = new LeakEvidence();
    private final String secret = hex(32);
    private final String secretSuffix = secret.substring(secret.length() - 24);
    private final String messageCanary = "MSG-CANARY-" + hex(8);
    private final String keyCanary = "AIzaSy-canary-" + hex(12);

    private static final Map<String, String> LEVELS = Map.of("logging.level.root", "DEBUG",
            "logging.level.org.springframework.web", "TRACE", "logging.level.org.apache.tomcat", "TRACE",
            "logging.level.org.apache.coyote", "TRACE", "logging.level.org.apache.catalina", "TRACE");

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        if (stub != null) {
            stub.stop(0);
        }
        LEVELS.keySet().forEach(System::clearProperty);
        if (logs != null) {
            logs.close();
        }
    }

    private void start(Map<String, String> env, FakeClockOption clock, Map<String, Object> singletons)
            throws IOException {
        logs = new LogCapture();
        // Every logger at DEBUG, and the web layers and the container at TRACE: the most any run would ever write.
        LEVELS.forEach(System::setProperty);
        Map<String, String> all = new HashMap<>(env);
        all.put("APPROVAL_SECRET", secret);
        service = TestService.start(dir, all, clock.clock, List.of(), Optional.empty(), singletons);
        evidence.canary("the approval secret's suffix", secretSuffix, Allowed.NOWHERE);
        evidence.canary("a message text", messageCanary, Allowed.ANY_RESPONSE);
        evidence.canary("the model key", keyCanary, Allowed.NOWHERE);
        evidence.database(dir.resolve("service.db"));
    }

    private record FakeClockOption(Optional<java.time.Clock> clock) {
        static final FakeClockOption REAL = new FakeClockOption(Optional.empty());

        static FakeClockOption of(FakeClock clock) {
            return new FakeClockOption(Optional.of(clock));
        }
    }

    private ApiClient client() {
        ApiClient api = new ApiClient(service.port());
        evidence.clients(api);
        return api;
    }

    private Session signIn(ApiClient api, String user) {
        Session s = api.signIn(user);
        evidence.canary("the session cookie of " + user, s.cookie(), Allowed.SET_COOKIE_HEADER);
        evidence.canary("the CSRF token of " + user, s.csrf(), Allowed.CSRF_HEADER, "user_session.csrf_token");
        return s;
    }

    private static String body(String message) {
        return "{\"message\":\"" + message + "\"}";
    }

    private HttpResponse<String> put(ApiClient api, Session s, String json) {
        return api.send("PUT", "/api/lab/faults", Optional.of(json), "Cookie", "ucplab_session=" + s.cookie(),
                "X-CSRF-Token", s.csrf(), "Content-Type", "application/json");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Map<String, Object> awaitRun(ApiClient api, Session s, String id, Predicate<String> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> run = json(api.get(s, "/api/runs/" + id));
        while (!done.test((String) run.get("status")) && System.nanoTime() < deadline) {
            pause(25);
            run = json(api.get(s, "/api/runs/" + id));
        }
        return run;
    }

    private Map<String, Object> awaitIntent(ApiClient api, Session s, String id, String state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Map<String, Object> now = json(api.get(s, "/api/intents/" + id));
        while (!state.equals(now.get("state")) && System.nanoTime() < deadline) {
            pause(25);
            now = json(api.get(s, "/api/intents/" + id));
        }
        return now;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return (List<String>) o;
    }

    /** Reads the whole scan after the service is closed, so shutdown's own lines are in it. */
    private void assertClean() {
        service.close();
        service = null;
        evidence.logs(logs.lines());
        assertThat(evidence.findings()).as("canaries found where they must not be").isEmpty();
    }

    // Covers: I8, 11, 23.1, 23.2, 23.3, 18.5 (the scripted flow of the plan: sign in, a run that asks and is answered, a
    // continuation, the approval GET (token kept) and approve, every read endpoint, each lab preset with the 503 window
    // and the sweeper, and shutdown with a run waiting: no canary anywhere but where it is delivered)
    @Test
    void scriptedFlow_leaksNothing() throws IOException {
        FakeClock clock = new FakeClock(T0);
        start(Map.of(), FakeClockOption.of(clock), Map.of());
        ApiClient api = client();
        Session alice = signIn(api, "alice");
        Session bob = signIn(api, "bob");

        // A run that asks, is answered, and proposes; then a continuation.
        Map<String, Object> asked = json(api.post(alice, "/api/runs", body("hi")));
        assertThat(asked).containsEntry("status", "waiting_for_user");
        String runId = (String) asked.get("run_id");
        Map<String, Object> answered = json(api.post(alice, "/api/runs/" + runId + "/messages",
                body("a blue mug please " + messageCanary)));
        assertThat(answered.get("status")).isIn("waiting_for_approval", "done");
        List<String> intentIds = list(answered.get("intent_ids"));
        assertThat(intentIds).hasSize(1);
        String intent = intentIds.getFirst();
        Map<String, Object> continued = json(api.post(alice, "/api/runs/" + runId + "/messages",
                body("and thanks " + messageCanary)));
        assertThat(continued.get("status")).isNotNull();

        // The approval: GET keeps the token, approve, and the intent completes.
        Map<String, Object> approval = json(api.get(alice, "/api/intents/" + intent + "/approval"));
        String token = (String) approval.get("token");
        assertThat(token).isNotBlank();
        evidence.canary("the approval token", token, Allowed.APPROVAL_BODY);
        assertThat(api.post(alice, "/api/intents/" + intent + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}").statusCode()).isEqualTo(200);
        assertThat(awaitIntent(api, alice, intent, "COMPLETED")).containsEntry("state", "COMPLETED");

        // Every read endpoint, as both users.
        for (Session s : List.of(alice, bob)) {
            for (String path : List.of("/api/me", "/api/intents", "/api/intents/" + intent,
                    "/api/intents/" + intent + "/events", "/api/intents/" + intent + "/approval",
                    "/api/runs/" + runId, "/api/lab/orders?merchant_id=merchant-a", "/healthz", "/readyz")) {
                api.get(s, path);
            }
        }

        // Each lab preset: the drop, the price change (nothing awaiting: refused), the 503 window ended by the sweeper
        // and by hand, merchant down, then orders and reset.
        for (String preset : List.of("drop_next_complete", "change_price", "merchant_503_20s", "merchant_down")) {
            put(api, alice, "{\"preset\":\"" + preset + "\"}");
        }
        put(api, alice, "{\"preset\":\"merchant_503_20s\"}");
        clock.advance(Duration.ofSeconds(21));
        service.bean(LabControls.class).sweep();
        pause(1500);
        api.send("DELETE", "/api/lab/faults", Optional.empty(), "Cookie", "ucplab_session=" + alice.cookie(),
                "X-CSRF-Token", alice.csrf());
        api.post(alice, "/api/lab/reset", "{}");

        // Shutdown with a run waiting in ask_user.
        Map<String, Object> waiting = json(api.post(bob, "/api/runs", body("hi")));
        assertThat(waiting).containsEntry("status", "waiting_for_user");

        assertClean();
        assertThat(logs.text()).contains("dev.ucplab.trace").contains("request method=");
    }

    /** Asks (the question holds the canary), then searches with the canary as the tool argument, then answers with it. */
    private ModelClient answeringModel() {
        return (conversation, tools) -> {
            long results = conversation.messages().stream()
                    .filter(m -> m instanceof dev.ucplab.core.agent.Message.ToolResult).count();
            if (results == 0) {
                return new ModelTurn("", List.of(new ToolCall("q1", "ask_user",
                        Map.of("question", "which one? " + messageCanary))), Usage.NONE);
            }
            if (results == 1) {
                return new ModelTurn("", List.of(new ToolCall("s1", "search_catalog",
                        Map.of("query", "mug " + messageCanary, "max_results", 3L))), Usage.NONE);
            }
            return new ModelTurn("MODEL-TEXT " + messageCanary, List.of(), Usage.NONE);
        };
    }

    // Covers: I8, 6.3 (a model that throws an Error, or an exception whose message holds the canary, ends the run failed;
    // the message reaches no log line and no response; the orphan rule ends a stored run that no instance holds)
    @Test
    void failingModels_andTheOrphanRule_leakNothing() throws IOException {
        FakeClock clock = new FakeClock(T0);
        AtomicReference<RuntimeException> boom = new AtomicReference<>();
        ModelClient model = (conversation, tools) -> {
            if (boom.get() != null) {
                throw boom.get();
            }
            throw new NoClassDefFoundError("Missing " + messageCanary + " " + keyCanary);
        };
        start(Map.of(), FakeClockOption.of(clock), Map.of("testModel", model));
        ApiClient api = client();
        Session alice = signIn(api, "alice");

        // The Error case.
        HttpResponse<String> first = api.post(alice, "/api/runs", body("buy a mug"));
        String firstId = (String) json(first).get("run_id");
        assertThat(awaitRun(api, alice, firstId, s -> !s.equals("running")))
                .containsEntry("status", "failed").containsEntry("reason", "internal_error");
        // An exception whose message holds the canary, in the same way a library's would.
        boom.set(new IllegalStateException("upstream said: Bearer " + keyCanary + " " + messageCanary));
        HttpResponse<String> second = api.post(alice, "/api/runs", body("buy a mug"));
        String secondId = (String) json(second).get("run_id");
        assertThat(awaitRun(api, alice, secondId, s -> !s.equals("running"))).containsEntry("status", "failed");

        // The orphan rule: a stored run with no outcome and no live run, read after the orphan time has passed.
        RunRepository runs = service.bean(RunRepository.class);
        RunId orphan = new RunId("orphan-" + hex(4));
        runs.insert(new UserId("local-alice"), RunRecord.started(orphan, new UserId("local-alice"), "scripted", T0));
        clock.advance(Duration.ofMinutes(7));
        Map<String, Object> ended = json(api.get(alice, "/api/runs/" + orphan.value()));
        assertThat(ended).containsEntry("status", "failed").containsEntry("reason", "interrupted");

        assertClean();
    }

    // Covers: I8, 6.5 (a run that asks and is answered, with a model whose texts hold the canary: the question, the answer,
    // a tool argument and the model's text are in the run's messages only, and in no trace line or log line)
    @Test
    void askAndAnswer_textsAreInTheMessagesOnly() throws IOException {
        start(Map.of(), FakeClockOption.REAL, Map.of("testModel", answeringModel()));
        ApiClient api = client();
        Session alice = signIn(api, "alice");

        Map<String, Object> asked = json(api.post(alice, "/api/runs", body("hello " + messageCanary)));
        assertThat(asked).containsEntry("status", "waiting_for_user");
        Map<String, Object> done = json(api.post(alice, "/api/runs/" + asked.get("run_id") + "/messages",
                body("the red one " + messageCanary)));
        assertThat(done).containsEntry("status", "done");
        assertThat(api.get(alice, "/api/runs/" + asked.get("run_id")).body()).contains(messageCanary);

        assertClean();
    }

    // Covers: I8, 6.5, 25.4 (gemini against a loopback stub that answers 401, 500 and a dropped connection, the 401 body
    // echoing the key as a real API's might: each run ends failed, and the key is in no log, response or file)
    @Test
    void geminiFailures_401_500_andADroppedConnection_leakNothing() throws IOException {
        AtomicReference<String> mode = new AtomicReference<>("401");
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            String key = ex.getRequestHeaders().getFirst("x-goog-api-key");
            ex.getRequestBody().readAllBytes();
            switch (mode.get()) {
                case "401" -> reply(ex, 401, "{\"error\":{\"code\":401,\"message\":\"API key not valid: " + key
                        + "\"}}");
                case "500" -> reply(ex, 500, "{\"error\":{\"code\":500,\"message\":\"internal; key " + key + "\"}}");
                default -> ex.getResponseBody().close();
            }
            ex.close();
        });
        stub.start();
        start(Map.of("AGENT_MODEL", "gemini", "GEMINI_API_KEY", keyCanary,
                "GEMINI_BASE_URL", "http://127.0.0.1:" + stub.getAddress().getPort()), FakeClockOption.REAL, Map.of());
        ApiClient api = client();
        Session alice = signIn(api, "alice");

        for (String m : List.of("401", "500", "drop")) {
            mode.set(m);
            Map<String, Object> run = json(api.post(alice, "/api/runs", body("show me a mug " + messageCanary)));
            String id = (String) run.get("run_id");
            assertThat(awaitRun(api, alice, id, s -> !s.equals("running")))
                    .as("the run with the stub in mode " + m).containsEntry("status", "failed");
        }

        assertClean();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }

    // Covers: I8, 9.1 (a payment token that is a canary, declined by the merchant: the token is in no log, response or
    // file, though it travelled in the complete request)
    @Test
    void thePaymentToken_declined_leaksNothing() throws IOException {
        String paymentCanary = "tok_canary_" + hex(10);
        start(Map.of(), FakeClockOption.REAL,
                Map.of("testPayment", PaymentInstrument.mockCard("pi_canary", paymentCanary)));
        evidence.canary("the payment token", paymentCanary, Allowed.NOWHERE, "merchant_attempt.request_body");
        ApiClient api = client();
        Session alice = signIn(api, "alice");
        Map<String, Object> asked = json(api.post(alice, "/api/runs", body("hi")));
        Map<String, Object> proposed = json(api.post(alice, "/api/runs/" + asked.get("run_id") + "/messages",
                body("a mug")));
        String intent = list(proposed.get("intent_ids")).getFirst();
        String token = (String) json(api.get(alice, "/api/intents/" + intent + "/approval")).get("token");
        evidence.canary("the approval token", token, Allowed.APPROVAL_BODY);

        assertThat(api.post(alice, "/api/intents/" + intent + "/approval",
                "{\"token\":\"" + token + "\",\"decision\":\"approve\"}").statusCode()).isEqualTo(200);

        assertThat(awaitIntent(api, alice, intent, "FAILED_NOT_CHARGED")).containsEntry("state", "FAILED_NOT_CHARGED");
        assertThat(service.merchant.orders()).isEmpty();
        api.get(alice, "/api/intents/" + intent + "/events");
        assertClean();
    }
}
