package dev.ucplab.eval;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.faultproxy.LogEntry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drives one scripted run in one lane (m4-plan section 2): reset the merchant, install the scenario's rules, build a
 * fresh agent, propose, let the auto-approver act and the recovery worker drive, wait for a terminal state (or abort at
 * the time limit), wait until the proxy has settled every request of the run, then collect the evidence.
 *
 * <p>All waiting is polling with a deadline, never a sleep sized to a hold.
 */
public final class RunScript {

    /** How often the harness looks at the database, the approver channel and the proxy log. */
    static final Duration POLL = Duration.ofMillis(20);
    /** S14: how many workers race (13.2). */
    public static final int RACERS = 24;

    /** What one S14 racer's {@code advance} did: its step, or the exception it threw, and how long it took. */
    record RaceOutcome(String workerId, Optional<StepResult> step, Optional<String> error, Duration took) {
    }

    private final Lane lane;
    private final TimeScale scale;
    private final Consumer<String> tokenSeen;
    private final Consumer<List<RaceOutcome>> raceSeen;

    public RunScript(Lane lane, TimeScale scale) {
        this(lane, scale, t -> { }, r -> { });
    }

    /** @param tokenSeen given every approval token the approver uses (the redaction test only) */
    RunScript(Lane lane, TimeScale scale, Consumer<String> tokenSeen) {
        this(lane, scale, tokenSeen, r -> { });
    }

    /** @param raceSeen given the outcome of every racer of an S14 run (its test only) */
    RunScript(Lane lane, TimeScale scale, Consumer<String> tokenSeen, Consumer<List<RaceOutcome>> raceSeen) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.scale = Objects.requireNonNull(scale, "scale");
        this.tokenSeen = Objects.requireNonNull(tokenSeen, "tokenSeen");
        this.raceSeen = Objects.requireNonNull(raceSeen, "raceSeen");
    }

    public TimeScale scale() {
        return scale;
    }

    /**
     * The time limit of a run: the 7.6 bound of the longest expected path (the reconcile deadline, one lease takeover
     * and two worker periods), plus 25%. A run that reaches it is aborted.
     */
    public Duration timeLimit() {
        return scale.orchestrator().reconcileDeadline().plus(scale.lease()).plus(scale.workerPeriod().multipliedBy(2))
                .multipliedBy(5).dividedBy(4);
    }

    /** The longest the harness waits, after the intent is terminal, for the proxy to settle every request of the run. */
    public Duration settleLimit() {
        return scale.lease().multipliedBy(2);
    }

    public RunEvidence run(Scenario scenario, int run, long seed) {
        Objects.requireNonNull(scenario, "scenario");
        Instant startedAt = Instant.now();
        String name = "L" + lane.index() + "-" + scenario.id() + "-r" + run;
        lane.merchant().reset();
        lane.proxy().install(scenario.rules().build(scale, run));
        long startSeq = lane.proxyLogMark();
        UserId user = new UserId("eval-" + name);
        List<IntentId> proposed = new ArrayList<>();
        List<AutoApprover.Decision> decisions = List.of();
        Optional<String> aborted = Optional.empty();
        Optional<Instant> terminalSeenAt = Optional.empty();
        List<RunEvidence.Persisted> persisted = new ArrayList<>();
        boolean race = scenario.driver() == Scenario.Driver.ADVANCE_RACE;
        Optional<Scenario.AdminRules> window = scenario.afterApproval().build(scale);
        Optional<Thread> clearer = Optional.empty();
        AtomicReference<String> windowFailed = new AtomicReference<>();
        List<AgentStack.Racer> racers = new ArrayList<>();
        try (AgentStack stack = new AgentStack(lane.agentFacingUrl(), lane.newDatabase(name), scale, seed,
                new SequentialIdGenerator(name), scenario.paymentToken(),
                race ? AgentStack.DECISION_ONLY : scale.orchestrator().lease())) {
            Supplier<AuthContext> auth = () -> new AuthContext(user, user.value() + "@eval.example",
                    stack.clock.instant(), "eval-session-" + name);
            AutoApprover approver = new AutoApprover(stack.approvalService, scenario.approver(), auth, stack.clock,
                    tokenSeen, () -> scenario.beforeFirstDecision().apply(lane.merchant(), scale));
            try {
                proposed.addAll(propose(stack, scenario, run, auth));
                Set<IntentId> ids = new LinkedHashSet<>(proposed);
                if (race) {
                    for (int i = 0; i < RACERS; i++) {
                        racers.add(stack.racer(new SequentialIdGenerator(name + "-w" + i), i));
                    }
                } else {
                    stack.loop.start();
                }
                Instant deadline = Instant.now().plus(timeLimit());
                while (true) {
                    for (IntentId id : ids) {
                        if (approver.tick(id)) {
                            if (window.isPresent() && clearer.isEmpty()) {
                                clearer = Optional.of(openWindow(window.get(), windowFailed));
                            }
                            if (race && !racers.isEmpty()) {
                                aborted = race(stack, id, racers);
                                racers.forEach(AgentStack.Racer::close);
                                racers.clear();
                                stack.loop.start();
                            } else {
                                // The first complete is not held back by the loop period (m4-plan section 2, step 5).
                                stack.worker.runOnce();
                            }
                        }
                    }
                    if (aborted.isPresent()) {
                        break;
                    }
                    if (ids.stream().allMatch(id -> done(stack, id))) {
                        terminalSeenAt = Optional.of(Instant.now());
                        break;
                    }
                    if (Instant.now().isAfter(deadline)) {
                        aborted = Optional.of("no terminal state within the time limit " + timeLimit());
                        break;
                    }
                    Thread.sleep(POLL);
                }
                stack.loop.close();
                if (clearer.isPresent() && !closeWindow(clearer.get(), window.get())) {
                    aborted = aborted.or(() -> Optional.of("the fault window was not cleared"));
                }
                if (windowFailed.get() != null) {
                    aborted = aborted.or(() -> Optional.of(windowFailed.get()));
                }
                if (!awaitSettled(startSeq)) {
                    aborted = aborted.or(() -> Optional.of("the proxy did not settle within " + settleLimit()));
                }
                for (IntentId id : ids) {
                    stack.intents.findForSystem(id).ifPresent(i -> persisted.add(new RunEvidence.Persisted(i,
                            stack.intents.attempts(id), stack.intents.events(id), stack.approvals.listForSystem(id))));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                aborted = Optional.of("interrupted");
            } catch (RuntimeException e) {
                aborted = Optional.of("harness error: " + e.getClass().getSimpleName());
            } finally {
                racers.forEach(AgentStack.Racer::close);
                clearer.ifPresent(Thread::interrupt);
            }
            decisions = approver.log();
        } catch (IOException e) {
            aborted = Optional.of("could not create the run's database: " + e.getClass().getSimpleName());
        }
        List<GroundTruth.Order> orders = lane.groundTruth().orders();
        Map<String, GroundTruth.Session> sessions = new LinkedHashMap<>();
        for (GroundTruth.Order o : orders) {
            lane.groundTruth().session(o.checkoutId()).ifPresent(s -> sessions.put(s.id(), s));
        }
        for (RunEvidence.Persisted p : persisted) {
            p.intent().sessionId().ifPresent(sid -> lane.groundTruth().session(sid.value())
                    .ifPresent(s -> sessions.put(s.id(), s)));
        }
        List<LogEntry> log = lane.proxy().log(startSeq);
        lane.proxyLogMark();
        return new RunEvidence(scenario.id(), run, seed, lane.index(), user, aborted, proposed, persisted, orders, sessions,
                log, decisions, startedAt, terminalSeenAt);
    }

    private static boolean done(AgentStack stack, IntentId id) {
        Optional<PurchaseIntent> intent = stack.intents.findForSystem(id);
        return intent.isPresent() && intent.get().state().isTerminal() && !intent.get().cancelPending();
    }

    private List<IntentId> propose(AgentStack stack, Scenario scenario, int run,
                                   Supplier<AuthContext> auth) throws InterruptedException {
        String rationale = "scripted run: " + scenario.id();
        return switch (scenario.driver()) {
            case PROPOSE_ONCE, ADVANCE_RACE -> List.of(stack.orchestrator.propose(auth.get(), scenario.target(), scenario.basket(),
                    rationale).intentId());
            case PROPOSE_TWICE -> {
                if (run % 2 == 0) {
                    List<IntentId> ids = new ArrayList<>();
                    for (int i = 0; i < 2; i++) {
                        ids.add(stack.orchestrator.propose(auth.get(), scenario.target(), scenario.basket(), rationale)
                                .intentId());
                    }
                    yield ids;
                }
                yield proposeTogether(stack, scenario, auth, rationale);
            }
        };
    }

    /** Two {@code propose} calls released at the same instant on two threads. */
    private static List<IntentId> proposeTogether(AgentStack stack, Scenario scenario,
                                                  Supplier<AuthContext> auth, String rationale)
            throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ProposalResult>> calls = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return stack.orchestrator.propose(auth.get(), scenario.target(), scenario.basket(), rationale);
                }));
            }
            go.countDown();
            List<IntentId> ids = new ArrayList<>();
            for (Future<ProposalResult> call : calls) {
                try {
                    ids.add(call.get().intentId());
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
                }
            }
            return ids;
        }
    }

    /**
     * S14: waits until the decision's lease has run out (it holds the intent only for its own write), then releases
     * every racer at the same instant and waits for all of them. Returns why the run must be aborted, if a racer threw.
     */
    private Optional<String> race(AgentStack stack, IntentId id, List<AgentStack.Racer> racers)
            throws InterruptedException {
        Instant limit = Instant.now().plus(scale.lease());
        while (stack.intents.findForSystem(id).flatMap(PurchaseIntent::leaseUntil)
                .filter(until -> !Instant.now().isAfter(until)).isPresent()) {
            if (Instant.now().isAfter(limit)) {
                return Optional.of("S14: the decision's lease did not run out within " + scale.lease());
            }
            Thread.sleep(1);
        }
        CountDownLatch ready = new CountDownLatch(racers.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RaceOutcome>> calls = new ArrayList<>();
        // Platform threads, one per racer, as separate worker processes would be.
        try (ExecutorService pool = Executors.newFixedThreadPool(racers.size())) {
            for (AgentStack.Racer r : racers) {
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    long t0 = System.nanoTime();
                    try {
                        StepResult step = r.orchestrator().advance(id);
                        return new RaceOutcome(r.workerId(), Optional.of(step), Optional.empty(),
                                Duration.ofNanos(System.nanoTime() - t0));
                    } catch (RuntimeException e) {
                        return new RaceOutcome(r.workerId(), Optional.empty(),
                                Optional.of(e.getClass().getSimpleName()), Duration.ofNanos(System.nanoTime() - t0));
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<RaceOutcome> outcomes = new ArrayList<>();
            for (Future<RaceOutcome> call : calls) {
                try {
                    outcomes.add(call.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            raceSeen.accept(List.copyOf(outcomes));
            return outcomes.stream().filter(o -> o.error().isPresent()).findFirst()
                    .map(o -> "S14 racer " + o.workerId() + " threw " + o.error().get());
        }
    }

    /**
     * S8: installs the window's rules through the proxy's admin API, and starts the thread that clears them through it
     * when the window ends. The wait is the fault's own length, not a guess at how long something takes.
     */
    private Thread openWindow(Scenario.AdminRules window, AtomicReference<String> failed) {
        admin("POST", window.rulesJson());
        return Thread.ofVirtual().name("eval-window-L" + lane.index()).start(() -> {
            try {
                Thread.sleep(window.clearAfter());
            } catch (InterruptedException e) {
                // Cleared below all the same.
            }
            try {
                admin("DELETE", "");
            } catch (RuntimeException e) {
                failed.set("could not clear the fault window: " + e.getMessage());
            }
        });
    }

    /** Waits for the window to end (or ends it now, if the run is over first). True once it was cleared. */
    private boolean closeWindow(Thread clearer, Scenario.AdminRules window) throws InterruptedException {
        if (!clearer.join(window.clearAfter().plus(settleLimit()))) {
            clearer.interrupt();
            return clearer.join(settleLimit());
        }
        return true;
    }

    /** One call to the proxy's admin API ({@code /_proxy/rules}); anything but 204 is an error. */
    private void admin(String method, String body) {
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest.Builder r = HttpRequest.newBuilder(lane.proxy().baseUrl().resolve("/_proxy/rules"))
                    .timeout(Duration.ofSeconds(5));
            if (method.equals("POST")) {
                r.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                r.DELETE();
            }
            int status = http.send(r.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 204) {
                throw new IllegalStateException(method + " /_proxy/rules answered " + status);
            }
        } catch (IOException e) {
            throw new IllegalStateException(method + " /_proxy/rules failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " /_proxy/rules interrupted", e);
        }
    }

    /** Polls the run's slice of the proxy log until every entry is settled, up to {@link #settleLimit()}. */
    private boolean awaitSettled(long startSeq) throws InterruptedException {
        Instant deadline = Instant.now().plus(settleLimit());
        while (!lane.proxy().log(startSeq).stream().allMatch(LogEntry::settled)) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            Thread.sleep(POLL);
        }
        return true;
    }
}
