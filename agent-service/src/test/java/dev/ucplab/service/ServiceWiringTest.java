package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.DefaultApprovalService;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.core.recovery.RecoveryWorker;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.service.config.ServiceProperties;
import dev.ucplab.service.config.StackConfiguration.Drivers;
import dev.ucplab.service.config.StackConfiguration.WorkerId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The service's own wiring against the reference merchant behind the real fault proxy (m6-plan section 2.4): a test of
 * drift between this wiring and the evaluation's {@code AgentStack}, which it does not share. A purchase and the S2
 * drop each give exactly one order; one worker id serves the approval service, the orchestrator and the worker, so an
 * approval completes through {@code driveNow} with no recovery pass; and shutdown keeps the database open until every
 * driver has finished.
 */
class ServiceWiringTest {

    private static final List<ProposedLine> TWO_SHIRTS = List.of(new ProposedLine("item_123", 2));
    private static final String COMPLETE = "/ucp/v1/checkout-sessions/*/complete";

    @TempDir
    Path dir;

    private static AuthContext alice(TestService service) {
        return new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev",
                service.bean(Clock.class).instant(), "session-alice");
    }

    /** Proposes, approves through the approval service and drives the intent at once, as the approval route does. */
    private static IntentId buy(TestService service, AuthContext auth) {
        ProposalResult proposed = service.bean(DefaultOrchestrator.class).propose(auth, ServiceProperties.MERCHANT,
                TWO_SHIRTS, "two shirts");
        assertThat(proposed.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        IntentId id = proposed.intentId();
        DefaultApprovalService approvals = service.bean(DefaultApprovalService.class);
        PendingApproval pending = approvals.pending(auth, id).orElseThrow();

        assertThat(approvals.decide(auth, id, pending.token(), true)).isEqualTo(new ApprovalDecision.Approved(id));
        assertThat(service.bean(RecoveryWorker.class).driveNow(id)).as("driveNow took the decision's lease").isTrue();
        return id;
    }

    private static PurchaseIntent awaitTerminal(TestService service, UserId owner, IntentId id) {
        SqlIntentRepository intents = service.bean(SqlIntentRepository.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            PurchaseIntent intent = intents.find(owner, id).orElseThrow();
            if (intent.state().isTerminal() || System.nanoTime() > deadline) {
                return intent;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // Covers: 7.6, 7.8, I1 (a purchase through the service's beans gives exactly one order; one worker id per process,
    // so the approval's lease is the one driveNow drives under and the intent completes before any recovery pass)
    @Test
    void purchase_throughTheBeans_givesOneOrder_throughDriveNow() throws IOException {
        try (TestService service = TestService.start(dir)) {
            String workerId = service.bean(WorkerId.class).value();
            assertThat(service.bean(RecoveryWorker.class).workerId()).isEqualTo(workerId);
            AuthContext auth = alice(service);

            IntentId id = buy(service, auth);
            PurchaseIntent done = awaitTerminal(service, auth.user(), id);

            assertThat(done.state()).isEqualTo(IntentState.COMPLETED);
            assertThat(done.orderId()).isPresent();
            assertThat(service.merchant.orders()).hasSize(1);
        }
    }

    // Covers: S2, I1, I4, I7 (the complete response is dropped after the merchant processed it: the service reconciles
    // and there is still exactly one order)
    @Test
    void s2_completeResponseDropped_stillGivesOneOrder() throws IOException {
        try (TestService service = TestService.start(dir)) {
            service.proxy.install(new RuleSet(2, List.of(new Rule("drop-complete",
                    Match.of("POST", COMPLETE).withNth(1), new Action.ForwardThenDropResponse()).withTimes(1))));
            AuthContext auth = alice(service);

            IntentId id = buy(service, auth);
            PurchaseIntent done = awaitTerminal(service, auth.user(), id);

            assertThat(done.state()).isEqualTo(IntentState.COMPLETED);
            assertThat(service.merchant.orders()).hasSize(1);
            assertThat(service.proxy.log(0)).anyMatch(e -> e.ruleId().filter("drop-complete"::equals).isPresent());
        }
    }

    // Covers: 7.6, m6-plan Stage 2 review point 6 (shutdown closes the loop, then drains the drivers, and only then the
    // database: a driver still running at shutdown can use the database until it finishes)
    @Test
    void shutdown_keepsTheDatabaseOpenUntilTheDriversFinish() throws Exception {
        TestService service = TestService.start(dir);
        Database db = service.bean(Database.class);
        ExecutorService drivers = service.bean(Drivers.class).executor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> driver = drivers.submit(() -> {
            running.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return selectOne(db);
        });
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> closing = CompletableFuture.runAsync(service::close);
        Thread.sleep(300);
        assertThat(closing).as("shutdown waits for the driver").isNotDone();
        release.countDown();
        closing.get(20, TimeUnit.SECONDS);

        assertThat(driver.get()).as("the driver's read ran on an open database").isTrue();
        assertThat(drivers.isTerminated()).isTrue();
        assertThatThrownBy(() -> selectOne(db)).as("the database closes last").isInstanceOf(RuntimeException.class);
    }

    // Covers: 23.4 (/readyz is 200 once the service has migrated its database; /healthz is 200)
    @Test
    void readyz_afterStartup_is200() throws Exception {
        try (TestService service = TestService.start(dir)) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> ready = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + service.port() + "/readyz")).build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + service.port() + "/healthz")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(ready.body()).contains("\"status\":\"ready\"");
            assertThat(health.statusCode()).isEqualTo(200);
        }
    }

    private static boolean selectOne(Database db) {
        return db.read(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT 1")) {
                return r.next();
            }
        });
    }
}
