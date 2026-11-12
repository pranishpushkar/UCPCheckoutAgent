package dev.ucplab.eval;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.faultproxy.LogEntry;
import java.io.IOException;
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

    private final Lane lane;
    private final TimeScale scale;
    private final Consumer<String> tokenSeen;

    public RunScript(Lane lane, TimeScale scale) {
        this(lane, scale, t -> { });
    }

    /** @param tokenSeen given every approval token the approver uses (the redaction test only) */
    RunScript(Lane lane, TimeScale scale, Consumer<String> tokenSeen) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.scale = Objects.requireNonNull(scale, "scale");
        this.tokenSeen = Objects.requireNonNull(tokenSeen, "tokenSeen");
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
        try (AgentStack stack = new AgentStack(lane, lane.newDatabase(name), scale, seed,
                new SequentialIdGenerator(name), scenario.paymentToken())) {
            Supplier<AuthContext> auth = () -> new AuthContext(user, user.value() + "@eval.example",
                    stack.clock.instant(), "eval-session-" + name);
            AutoApprover approver = new AutoApprover(stack.approvalService, scenario.approver(), auth, stack.clock,
                    tokenSeen, () -> scenario.beforeFirstDecision().apply(lane.merchant()));
            try {
                proposed.addAll(propose(stack, scenario, run, auth));
                Set<IntentId> ids = new LinkedHashSet<>(proposed);
                stack.loop.start();
                Instant deadline = Instant.now().plus(timeLimit());
                while (true) {
                    for (IntentId id : ids) {
                        if (approver.tick(id)) {
                            // The first complete is not held back by the loop period (m4-plan section 2, step 5).
                            stack.worker.runOnce();
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
                stack.loop.close();
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
            case PROPOSE_ONCE -> List.of(stack.orchestrator.propose(auth.get(), scenario.target(), scenario.basket(),
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
