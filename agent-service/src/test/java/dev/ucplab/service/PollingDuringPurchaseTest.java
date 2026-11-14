package dev.ucplab.service;

import static dev.ucplab.service.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.service.ApiClient.Session;
import dev.ucplab.service.config.ServiceProperties;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Polling must not hurt a purchase (m6-plan C18, carried from the Stage 2 review): a console polls
 * {@code GET /api/runs/{id}} (which takes the database write lock to check the orphan rule) and
 * {@code GET /api/intents/{id}} once a second, and a purchase through the S2 drop (the complete's response is lost, so
 * the agent reconciles) must still reach COMPLETED with one order, and no poll may fail with a busy or locked database.
 * Here the polls run as fast as the client can, from two threads, for the whole purchase. Covers: 7, S2, 21.3, 23.1.
 */
class PollingDuringPurchaseTest {

    @TempDir
    Path dir;

    private record Outcome(Duration purchase, int polls, List<String> failures, int orders, String state) {}

    private Outcome purchase(Path where, boolean polling) throws Exception {
        try (TestService service = TestService.start(where)) {
            ApiClient api = new ApiClient(service.port());
            Session alice = api.signIn("alice");
            Clock clock = service.bean(Clock.class);
            AuthContext auth = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(),
                    "test-session");
            ProposalResult proposed = service.bean(DefaultOrchestrator.class).propose(auth, ServiceProperties.MERCHANT,
                    List.of(new ProposedLine("item_456", 1)), "polling test");
            assertThat(proposed.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
            IntentId intent = proposed.intentId();
            // A run waiting in ask_user, so the run GET has a stored run to read.
            String run = (String) json(api.post(alice, "/api/runs", "{\"message\":\"hi\"}")).get("run_id");
            assertThat(api.send("PUT", "/api/lab/faults", Optional.of("{\"preset\":\"drop_next_complete\"}"), "Cookie",
                    "ucplab_session=" + alice.cookie(), "X-CSRF-Token", alice.csrf(), "Content-Type",
                    "application/json").statusCode()).isEqualTo(200);
            String token = (String) json(api.get(alice, "/api/intents/" + intent.value() + "/approval")).get("token");

            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger polls = new AtomicInteger();
            List<String> failures = new CopyOnWriteArrayList<>();
            List<Thread> pollers = new java.util.ArrayList<>();
            if (polling) {
                for (String path : List.of("/api/runs/" + run, "/api/intents/" + intent.value())) {
                    Thread t = Thread.ofPlatform().start(() -> {
                        ApiClient poller = new ApiClient(service.port());
                        while (!stop.get()) {
                            HttpResponse<String> r = poller.get(alice, path);
                            polls.incrementAndGet();
                            if (r.statusCode() != 200 || r.body().toLowerCase().contains("lock")
                                    || r.body().toLowerCase().contains("busy")) {
                                failures.add(path + " -> " + r.statusCode() + " " + r.body());
                            }
                            poller.seen.clear();
                        }
                    });
                    pollers.add(t);
                }
            }
            long start = System.nanoTime();
            assertThat(api.post(alice, "/api/intents/" + intent.value() + "/approval",
                    "{\"token\":\"" + token + "\",\"decision\":\"approve\"}").statusCode()).isEqualTo(200);
            String state = "";
            long deadline = start + Duration.ofSeconds(60).toNanos();
            while (!"COMPLETED".equals(state) && System.nanoTime() < deadline) {
                state = (String) json(api.get(alice, "/api/intents/" + intent.value())).get("state");
                Thread.sleep(10);
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            stop.set(true);
            for (Thread t : pollers) {
                t.join(10_000);
            }
            return new Outcome(took, polls.get(), failures, service.merchant.orders().size(), state);
        }
    }

    // Covers: 7, S2, 23.1 (a purchase through the S2 drop completes with one order while two clients poll the run and the
    // intent as fast as they can: no failed poll, and the time with and without polling is printed)
    @Test
    void purchaseThroughTheS2Drop_completes_whileTwoClientsPollAsFastAsTheyCan() throws Exception {
        Outcome quiet = purchase(Files.createDirectory(dir.resolve("quiet")), false);
        Outcome busy = purchase(Files.createDirectory(dir.resolve("busy")), true);

        System.out.println("polling measurement: purchase without polling " + quiet.purchase().toMillis()
                + " ms; with polling " + busy.purchase().toMillis() + " ms over " + busy.polls() + " polls");
        assertThat(quiet.state()).isEqualTo("COMPLETED");
        assertThat(quiet.orders()).isEqualTo(1);
        assertThat(busy.state()).isEqualTo("COMPLETED");
        assertThat(busy.orders()).as("orders at the merchant").isEqualTo(1);
        assertThat(busy.failures()).as("polls that failed").isEmpty();
        assertThat(busy.polls()).as("polls made during the purchase").isGreaterThan(20);
    }
}
