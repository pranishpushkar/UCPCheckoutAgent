package dev.ucplab.eval;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.PathPattern;
import dev.ucplab.faultproxy.RuleSet;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
import java.util.concurrent.Semaphore;
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
    private final Semaphore childSlots;
    /** Run by a fault window's clearer just before its DELETE; only a test sets it, to make a late clear late. */
    private volatile Runnable beforeClear = () -> { };

    public RunScript(Lane lane, TimeScale scale) {
        this(lane, scale, t -> { }, r -> { }, new Semaphore(ChildProcess.MAX_CHILDREN));
    }

    /**
     * @param childSlots shared by every lane of an evaluation: a run that starts agent child processes holds one slot
     *     for its whole length, so at most {@link ChildProcess#MAX_CHILDREN} child JVMs run at once
     */
    public RunScript(Lane lane, TimeScale scale, Semaphore childSlots) {
        this(lane, scale, t -> { }, r -> { }, childSlots);
    }

    /** @param tokenSeen given every approval token the approver uses (the redaction test only) */
    RunScript(Lane lane, TimeScale scale, Consumer<String> tokenSeen, Semaphore childSlots) {
        this(lane, scale, tokenSeen, r -> { }, childSlots);
    }

    /** @param raceSeen given the outcome of every racer of an S14 run (its test only) */
    RunScript(Lane lane, TimeScale scale, Consumer<String> tokenSeen, Consumer<List<RaceOutcome>> raceSeen,
              Semaphore childSlots) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.scale = Objects.requireNonNull(scale, "scale");
        this.tokenSeen = Objects.requireNonNull(tokenSeen, "tokenSeen");
        this.raceSeen = Objects.requireNonNull(raceSeen, "raceSeen");
        this.childSlots = Objects.requireNonNull(childSlots, "childSlots");
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
        if (scenario.driver() != Scenario.Driver.KILL_AND_RESTART) {
            return run(scenario, run, seed, Optional.empty());
        }
        try {
            // Held for the whole run: the restart must not wait behind another lane's child (S9's takeover margin).
            childSlots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a child slot", e);
        }
        try {
            return run(scenario, run, seed, Optional.of(Scenarios.killPoint(scenario, run)));
        } finally {
            childSlots.release();
        }
    }

    private RunEvidence run(Scenario scenario, int run, long seed, Optional<KillPoint> killPoint) {
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
        Optional<Scenario.AdminRules> window = scenario.faultWindow().build(scale);
        AtomicReference<Thread> clearer = new AtomicReference<>();
        AtomicReference<String> windowFailed = new AtomicReference<>();
        List<AgentStack.Racer> racers = new ArrayList<>();
        Children children = new Children(name, seed);
        try (AgentStack stack = new AgentStack(lane.agentFacingUrl(), lane.newDatabase(name), scale, seed,
                new SequentialIdGenerator(name), scenario.paymentToken(),
                race || killPoint.isPresent() ? AgentStack.DECISION_ONLY : scale.orchestrator().lease())) {
            Supplier<AuthContext> auth = () -> new AuthContext(user, user.value() + "@eval.example",
                    stack.clock.instant(), "eval-session-" + name);
            // The window opens before the decision, as the merchant changes do: after it the loop may send the first
            // complete at any moment. The clear timer starts when the rules are installed.
            AutoApprover approver = new AutoApprover(stack.approvalService, scenario.approver(), auth, stack.clock,
                    tokenSeen, () -> {
                        window.ifPresent(w -> clearer.set(openWindow(w, windowFailed)));
                        scenario.beforeFirstDecision().apply(lane.merchant(), scale);
                    });
            try {
                if (killPoint.isPresent()) {
                    // The agent child is the worker; it is running before the intent exists.
                    children.start(stack.dbFile());
                }
                proposed.addAll(propose(stack, scenario, run, auth));
                Set<IntentId> ids = new LinkedHashSet<>(proposed);
                if (killPoint.isPresent()) {
                    // No loop here: only the child drives the intent.
                } else if (race) {
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
                            if (killPoint.isPresent()) {
                                // The child's next pass takes the APPROVED intent.
                            } else if (race && !racers.isEmpty()) {
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
                    if (killPoint.isPresent() && children.kills() == 0 && killPoint.get().due(lane.proxy().log(startSeq),
                            stack.intents.findForSystem(proposed.getFirst()), scale, Instant.now())) {
                        aborted = children.killAndRestart(stack.dbFile());
                        if (aborted.isPresent()) {
                            break;
                        }
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
                if (killPoint.isPresent() && aborted.isEmpty() && children.kills() == 0) {
                    aborted = Optional.of("the intent ended before kill point " + killPoint.get() + " was reached");
                }
                aborted = aborted.or(children::stop);
                stack.loop.close();
                if (clearer.get() != null && !closeWindow(clearer.get(), window.get())) {
                    aborted = aborted.or(() -> Optional.of("the fault window was not cleared"));
                }
                if (windowFailed.get() != null) {
                    aborted = aborted.or(() -> Optional.of(windowFailed.get()));
                }
                if (!awaitSettled(startSeq)) {
                    aborted = aborted.or(() -> Optional.of("the proxy did not settle within " + settleLimit()));
                }
                if (window.isPresent()) {
                    aborted = aborted.or(() -> windowMissed(window.get(), lane.proxy().log(startSeq)));
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
            } catch (IOException e) {
                aborted = Optional.of("agent child: " + e.getMessage());
            } finally {
                racers.forEach(AgentStack.Racer::close);
                // A window still open (an aborted run) is cleared now, and the run does not end before the clear has
                // landed: a late DELETE would wipe the rules of the lane's next run.
                Thread c = clearer.get();
                if (c != null) {
                    c.interrupt();
                    if (!joined(c, settleLimit())) {
                        aborted = aborted.or(() -> Optional.of("the fault window's clear was still pending after "
                                + settleLimit()));
                    }
                }
                children.close();
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
                log, decisions, startedAt, terminalSeenAt, children.kills(), children.records());
    }

    /**
     * The agent child processes of one S9 or S28 run (m4-plan section 8): one is running at a time, each with a log
     * file next to the run's database, and the kill and the restart go through here.
     */
    private final class Children implements AutoCloseable {
        /** How long a child JVM may take to print READY. */
        private static final Duration READY_LIMIT = Duration.ofSeconds(30);

        private final String name;
        private final long seed;
        private final List<RunEvidence.Child> done = new ArrayList<>();
        private ChildProcess current;
        private String currentWorker;
        private Path currentLog;
        private Path currentDb;
        private int kills;

        Children(String name, long seed) {
            this.name = name;
            this.seed = seed;
        }

        int kills() {
            return kills;
        }

        void start(Path db) throws IOException, InterruptedException {
            int n = done.size() + 1;
            currentDb = db;
            currentLog = db.resolveSibling(name + "-child-" + n + ".log");
            current = ChildProcess.startAgent(db, lane.agentFacingUrl(), scale, seed + n, currentLog);
            currentWorker = current.awaitReady(READY_LIMIT);
        }

        /** kill -9, then a new child on the same file at once. Returns why the run must abort, if it must. */
        Optional<String> killAndRestart(Path db) throws IOException, InterruptedException {
            int exit = current.kill();
            kills++;
            finish(true, exit);
            if (exit != ChildProcess.SIGKILL_EXIT) {
                return Optional.of("the agent child exited " + exit + " after destroyForcibly, not "
                        + ChildProcess.SIGKILL_EXIT + " (SIGKILL)");
            }
            start(db);
            return Optional.empty();
        }

        /** Stops the running child normally at the end of the run. */
        Optional<String> stop() {
            if (current == null) {
                return Optional.empty();
            }
            try {
                finish(false, current.stop());
                return Optional.empty();
            } catch (IOException e) {
                return Optional.of("agent child: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.of("interrupted");
            }
        }

        private void finish(boolean killed, int exit) throws IOException {
            done.add(new RunEvidence.Child(currentWorker, currentDb.getFileName().toString(), killed, exit,
                    current.log()));
            current = null;
        }

        List<RunEvidence.Child> records() {
            return List.copyOf(done);
        }

        /** An aborted run: kill whatever is still running, and keep its log. */
        @Override
        public void close() {
            if (current != null) {
                current.close();
                try {
                    done.add(new RunEvidence.Child(currentWorker == null ? "(not ready)" : currentWorker,
                            currentDb.getFileName().toString(), true, -1, current.log()));
                } catch (IOException e) {
                    // The log is lost; the abort reason already says why.
                }
                current = null;
            }
        }
    }

    private static boolean done(AgentStack stack, IntentId id) {
        Optional<PurchaseIntent> intent = stack.intents.findForSystem(id);
        return intent.isPresent() && intent.get().state().isTerminal() && !intent.get().cancelPending();
    }

    private List<IntentId> propose(AgentStack stack, Scenario scenario, int run,
                                   Supplier<AuthContext> auth) throws InterruptedException {
        String rationale = "scripted run: " + scenario.id();
        return switch (scenario.driver()) {
            case PROPOSE_ONCE, ADVANCE_RACE, KILL_AND_RESTART -> List.of(stack.orchestrator.propose(auth.get(),
                    scenario.target(), scenario.basket(), rationale).intentId());
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
                beforeClear.run();
                admin("DELETE", "");
            } catch (RuntimeException e) {
                failed.set("could not clear the fault window: " + e.getMessage());
            }
        });
    }

    /** Waits up to {@code limit} for {@code t} to end; false if it is still running (or this thread is interrupted). */
    private static boolean joined(Thread t, Duration limit) {
        try {
            return t.join(limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !t.isAlive();
        }
    }

    /** Test seam: {@code r} runs on the clearer thread just before it clears a fault window. */
    void beforeClear(Runnable r) {
        beforeClear = Objects.requireNonNull(r, "r");
    }

    /**
     * The row's own claim (S8): its first complete on the wire was caught by a rule of the window. If it was not, the
     * run never tested the outage, and it is aborted rather than counted as a pass.
     */
    static Optional<String> windowMissed(Scenario.AdminRules window, List<LogEntry> log) {
        PathPattern complete = new PathPattern(Scenarios.COMPLETE);
        Set<String> ids = new java.util.TreeSet<>();
        RuleSet.parse(window.rulesJson()).rules().forEach(r -> ids.add(r.id()));
        Optional<LogEntry> first = log.stream().filter(l -> l.method().equals("POST") && complete.matches(l.path()))
                .findFirst();
        if (first.isEmpty()) {
            return Optional.of("no complete reached the proxy, so the outage " + ids + " was never tested");
        }
        if (first.get().ruleId().filter(ids::contains).isEmpty()) {
            return Optional.of("the first complete on the wire was not caught by the outage " + ids
                    + ", so the run never tested it");
        }
        return Optional.empty();
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
