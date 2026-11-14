package dev.ucplab.eval.interop;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.eval.AgentStack;
import dev.ucplab.eval.Scenarios;
import dev.ucplab.eval.TimeScale;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.persistence.Migrations;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * The H1 rows against the official sample merchant, orchestrator-direct (H1 plan sections 6 and 13.2): the sample behind
 * the fault proxy, the agent's real stack (SQLite, gateway, orchestrator) wired for the sample, a scripted approver, and
 * ground truth from the sample's own {@code orders} table. A row that cannot read that table claims no I1.
 */
final class InteropRows {

    static final String ITEM = "bouquet_roses";
    static final List<ProposedLine> BASKET = List.of(new ProposedLine(ITEM, 1));
    static final MerchantId SHOP = Scenarios.SHOP;

    /** One row: what the proxy does, what the harness changes at the sample before the decision, the card, the answer. */
    record Row(String id, String title, BiFunction<TimeScale, Integer, RuleSet> rules, BiConsumer<SampleMerchant, long[]> before,
               String token, Set<IntentState> expected, boolean twice, boolean sameTotalAsApproved, Mode mode) {
        Row(String id, String title, BiFunction<TimeScale, Integer, RuleSet> rules, BiConsumer<SampleMerchant, long[]> before,
            String token, Set<IntentState> expected, boolean twice, boolean sameTotalAsApproved) {
            this(id, title, rules, before, token, expected, twice, sameTotalAsApproved, Mode.NORMAL);
        }
    }

    /** How the run is driven beyond propose, approve and advance. */
    enum Mode { NORMAL, RACE, WINDOW }

    /** What one run saw. */
    record Result(String row, int run, IntentState state, int newOrders, long millis, List<String> violations) {
    }

    private static RuleSet rules(int run, Rule... rules) {
        return new RuleSet(run, List.of(rules));
    }

    static List<Row> rows(String origin) {
        BiConsumer<SampleMerchant, long[]> none = (m, s) -> { };
        String create = "/checkout-sessions";
        String complete = "/checkout-sessions/*/complete";
        return List.of(
                new Row("S0", "no fault", (t, r) -> RuleSet.empty(), none, SampleWiring.SUCCESS_TOKEN,
                        Set.of(IntentState.COMPLETED), false, false),
                new Row("S1", "connection closed before forwarding on the first 2 creates",
                        (t, r) -> rules(r, new Rule("close-create", Match.of("POST", create),
                                Action.FailBeforeForward.closeConnection()).withTimes(2)), none, SampleWiring.SUCCESS_TOKEN,
                        Set.of(IntentState.COMPLETED), false, false),
                new Row("S2", "complete response dropped after the merchant processed it",
                        (t, r) -> rules(r, new Rule("drop-complete", Match.of("POST", complete).withNth(1),
                                new Action.ForwardThenDropResponse()).withTimes(1)), none, SampleWiring.SUCCESS_TOKEN,
                        Set.of(IntentState.COMPLETED), false, false),
                new Row("S3", "complete response delayed past the client timeout",
                        (t, r) -> rules(r, new Rule("hold-complete", Match.of("POST", complete).withNth(1),
                                new Action.ForwardThenDelayResponse(t.s3Hold().toMillis())).withTimes(1)), none,
                        SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.COMPLETED), false, false),
                new Row("S4", "the same proposal submitted twice", (t, r) -> RuleSet.empty(), none, SampleWiring.SUCCESS_TOKEN,
                        Set.of(IntentState.COMPLETED), true, false),
                new Row("S5", "the price changes in the sample's products table after the quote: complete charges the quote",
                        (t, r) -> RuleSet.empty(), (m, s) -> {
                            s[0] = m.db().price(ITEM);
                            m.setPrice(ITEM, s[0] + 200);
                        }, SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.COMPLETED), false, true),
                new Row("S6", "the item sells out after the quote", (t, r) -> RuleSet.empty(),
                        (m, s) -> {
                            s[1] = m.db().stock(ITEM);
                            m.setStock(ITEM, 0);
                        }, SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.FAILED_NOT_CHARGED), false, false),
                new Row("S7", "payment declined", (t, r) -> RuleSet.empty(), none, SampleWiring.FAIL_TOKEN,
                        Set.of(IntentState.FAILED_NOT_CHARGED), false, false),
                new Row("S8", "503 on every checkout call for 20 s / k, then recovery", (t, r) -> RuleSet.empty(), none,
                        SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.COMPLETED, IntentState.ESCALATED), false, false, Mode.WINDOW),
                new Row("S13", "duplicate_request on complete (parallel in even runs, sequential in odd)",
                        (t, r) -> rules(r, new Rule("duplicate-complete", Match.of("POST", complete).withNth(1),
                                new Action.DuplicateRequest(r % 2 == 0 ? Action.DuplicateRequest.Mode.PARALLEL
                                        : Action.DuplicateRequest.Mode.SEQUENTIAL)).withTimes(1)), none,
                        SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.COMPLETED), false, false),
                new Row("S14", "24 workers advance one APPROVED intent together", (t, r) -> RuleSet.empty(), none,
                        SampleWiring.SUCCESS_TOKEN, Set.of(IntentState.COMPLETED), false, false, Mode.RACE));
    }

    /** Runs one row once against a lane's sample and proxy. */
    static Result run(Row row, int run, SampleMerchant sample, FaultProxy proxy, ProfileServer profile, Path work, TimeScale scale)
            throws Exception {
        return run(row, run, sample, proxy, profile, work, scale, profile.ucpAgent());
    }

    static Result run(Row row, int run, SampleMerchant sample, FaultProxy proxy, ProfileServer profile, Path work, TimeScale scale,
            String ucpAgent) throws Exception {
        long started = System.nanoTime();
        List<String> violations = new ArrayList<>();
        Path db = work.resolve(row.id() + "-" + run + ".db");
        Migrations.migrate(db);
        long[] saved = new long[2];
        int ordersBefore = sample.orders().size();
        proxy.install(row.rules().apply(scale, run));
        IntentState state;
        long approvedTotal = -1;
        String reason = "";
        String sessionId = "";
        try (AgentStack stack = new AgentStack(proxy.baseUrl(), db, scale, run, new SequentialIdGenerator(row.id() + "r" + run),
                row.token(), row.mode() == Mode.RACE ? AgentStack.DECISION_ONLY : scale.orchestrator().lease(), "", SampleWiring.of(ucpAgent))) {
            UserId user = new UserId("interop-user");
            AuthContext auth = new AuthContext(user, "john.doe@example.com", stack.clock.instant(), "interop-session");
            IntentId id = stack.orchestrator.propose(auth, SHOP, BASKET, "interop " + row.id()).intentId();
            if (row.twice()) {
                IntentId again = stack.orchestrator.propose(auth, SHOP, BASKET, "interop " + row.id()).intentId();
                if (!again.equals(id)) {
                    violations.add("S4: the second identical proposal made another intent");
                }
            }
            boolean changed = false;
            Instant deadline = Instant.now().plus(scale.orchestrator().reconcileDeadline().multipliedBy(3).plusSeconds(60));
            state = IntentState.QUOTING;
            while (Instant.now().isBefore(deadline)) {
                StepResult r = stack.orchestrator.advance(id);
                state = r.after();
                if (Boolean.getBoolean("interop.trace")) {
                    System.err.println("[trace] " + row.id() + " run " + run + " " + r.before() + " -> " + r.after() + " idle=" + r.idle());
                }
                if (state.isTerminal()) {
                    break;
                }
                if (state == IntentState.AWAITING_APPROVAL && r.idle()) {
                    var pending = stack.approvalService.pending(auth, id).orElseThrow();
                    approvedTotal = pending.snapshot().totalMinor();
                    if (!changed) {
                        row.before().accept(sample, saved);
                        changed = true;
                    }
                    if (row.mode() == Mode.WINDOW) {
                        proxy.install(new RuleSet(run, List.of(
                                new Rule("outage-post", Match.of("POST", "/checkout-sessions/**"), Action.FailBeforeForward.status(503)),
                                new Rule("outage-put", Match.of("PUT", "/checkout-sessions/**"), Action.FailBeforeForward.status(503)),
                                new Rule("outage-get", Match.of("GET", "/checkout-sessions/**"), Action.FailBeforeForward.status(503)))));
                        Thread clear = new Thread(() -> {
                            try {
                                Thread.sleep(scale.s8Outage().toMillis());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            proxy.clearRules();
                        }, "interop-s8-clear");
                        clear.setDaemon(true);
                        clear.start();
                    }
                    stack.approvalService.decide(auth, id, pending.token(), true);
                    if (row.mode() == Mode.RACE) {
                        race(stack, id, run, scale);
                    }
                    continue;
                }
                long wait = r.nextAttemptAt().map(t -> Math.max(10, Duration.between(Instant.now(), t).toMillis())).orElse(50L);
                Thread.sleep(Math.min(wait, 250));
            }
            if (Boolean.getBoolean("interop.trace") && !state.isTerminal() || Boolean.getBoolean("interop.trace")) {
                stack.intents.attempts(id).forEach(x -> System.err.println("[attempt] " + x.op() + " rev " + x.revision() + " http "
                        + x.httpStatus() + "\n   req " + x.requestBody().map(b -> b.json()).orElse("") + "\n   resp " + x.responseJson().orElse("")));
            }
            sessionId = stack.intents.findForSystem(id).flatMap(i -> i.sessionId()).map(x -> x.value()).orElse("");
            reason = stack.intents.findForSystem(id).flatMap(i -> i.failureReason()).orElse("");
            if (!state.isTerminal()) {
                violations.add("not terminal within the bound: " + state);
            }
        } finally {
            proxy.clearRules();
            if (row.id().equals("S5")) {
                sample.setPrice(ITEM, saved[0]);
            }
            if (row.id().equals("S6")) {
                sample.setStock(ITEM, (int) saved[1]);
            }
        }
        List<SampleOrder> orders = sample.orders();
        int newOrders = orders.size() - ordersBefore;
        if (!row.expected().contains(state)) {
            violations.add("expected " + row.expected() + " but " + state + (reason.isEmpty() ? "" : " (" + reason + ")"));
        }
        violations.addAll(groundTruth(state, sessionId, orders.subList(ordersBefore, orders.size()), approvedTotal));
        return new Result(row.id(), run, state, newOrders, (System.nanoTime() - started) / 1_000_000, violations);
    }

    /** S14: 24 workers on the same database file call advance together once the decision's lease has run out. */
    private static void race(AgentStack stack, IntentId id, int run, TimeScale scale) throws Exception {
        Thread.sleep(20);
        List<AgentStack.Racer> racers = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                racers.add(stack.racer(new SequentialIdGenerator("S14r" + run + "w" + i), i));
            }
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (AgentStack.Racer r : racers) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                        r.orchestrator().advance(id);
                    } catch (RuntimeException | InterruptedException e) {
                        // a lost race is expected; the table decides
                    }
                });
                threads.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : threads) {
                t.join();
            }
        } finally {
            racers.forEach(AgentStack.Racer::close);
        }
    }

    /**
     * I1 and I2 from the sample's own table (H1 C8b): a COMPLETED intent has exactly one new order, it carries the intent's
     * own session id (not just any order: the count alone would pass a foreign one), and its total is the approved total,
     * shipping included. Any other end has none.
     */
    static List<String> groundTruth(IntentState state, String sessionId, List<SampleOrder> newOrders, long approvedTotal) {
        List<String> out = new ArrayList<>();
        if (state != IntentState.COMPLETED) {
            if (!newOrders.isEmpty()) {
                out.add("I1: " + newOrders.size() + " new order(s) in the sample's orders table for " + state);
            }
            return out;
        }
        if (newOrders.size() != 1) {
            out.add("I1: " + newOrders.size() + " new order(s) in the sample's orders table; expected 1 for COMPLETED");
        }
        for (SampleOrder o : newOrders) {
            if (!o.checkoutId().equals(sessionId)) {
                out.add("I1: the new order " + o.id() + " is for checkout " + o.checkoutId() + ", not this intent's " + sessionId);
            } else if (o.totalMinor() != approvedTotal) {
                out.add("I2: order total " + o.totalMinor() + " is not the approved total " + approvedTotal);
            }
        }
        return out;
    }

    static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }
}
