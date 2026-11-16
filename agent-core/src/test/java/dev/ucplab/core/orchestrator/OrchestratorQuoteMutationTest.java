package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.RewritingGateway.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.DuplicateIntentException;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.FulfillmentView;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.NoGatewayException;
import dev.ucplab.core.merchant.NoInstrumentException;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;

/**
 * Proposing and quoting (7.2, 7.4, 7.6, 8.1, 9.1, I2, I4, I9, I11), found unchecked by the H3 P7 mutation pass, group 1:
 * what a proposal records and returns, how a quote cycle ends or goes on, what each answer to a create or an update leads to,
 * the shipping selection steps and their limit, and the polling time the caller is told to wait. Each test asserts a state, a
 * failure reason, an event, a stored attempt or a call that did or did not reach the merchant.
 */
class OrchestratorQuoteMutationTest {

    private Lab lab = new Lab();
    private Lab.Worker w = lab.worker("worker-a");

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> attempts(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private void fresh(Consumer<Lab> setup) {
        lab = new Lab();
        setup.accept(lab);
        w = lab.worker("worker-a");
    }

    // ---------------------------------------------------------------- propose

    // Covers: 7.7, 7.2. Kills DefaultOrchestrator.propose:262 RemoveConditional_EQUAL_ELSE (a proposal with no line is refused)
    @Test
    void aProposalWithNoLine_isRefused() {
        assertThatThrownBy(() -> w.orchestrator().propose(lab.alice(), SHOP, List.of(), "nothing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lab.merchant.log()).isEmpty();
    }

    // Covers: 7.7, I1. Kills DefaultOrchestrator.propose:267 RemoveConditional_EQUAL_ELSE: the same basket proposed again while
    // the first intent is active returns that intent: one intent, one create, no second "proposed" event
    @Test
    void theSameProposalTwice_returnsTheFirstIntent() {
        ProposalResult first = lab.propose(w, TWO_SHIRTS);

        ProposalResult second = lab.propose(w, TWO_SHIRTS);

        assertThat(second.intentId()).isEqualTo(first.intentId());
        assertThat(second.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(second.quote()).as("a repeat does not carry a quote").isEmpty();
        assertThat(attempts(first.intentId(), AttemptOp.CREATE)).hasSize(1);
        assertThat(events(first.intentId(), "proposed")).hasSize(1);
    }

    private IntentRepository racing(Runnable beforeInsert) {
        return (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, m, args) -> {
                    if (m.getName().equals("insert")) {
                        beforeInsert.run();
                        throw new DuplicateIntentException("lost the insert race");
                    }
                    try {
                        return m.invoke(lab.intents, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // Covers: 7.7, I1, I9. Kills DefaultOrchestrator.propose:286 NullReturnVals (NO_COVERAGE) and lambda$propose$2: when two
    // proposals race and this one loses the insert, it returns the intent that won, not a second one; when nothing won, the
    // refusal reaches the caller
    @Test
    void aProposalThatLosesTheInsertRace_returnsTheWinner_orRefuses() {
        IntentId winner = new IntentId("winner");
        String key = DefaultOrchestrator.dedupeKey(ALICE, SHOP, TWO_SHIRTS);
        Lab.Worker loser = lab.worker("worker-b", lab.merchant, lab.approvals, racing(() -> lab.intents.insert(
                PurchaseIntent.proposed(winner, ALICE, SHOP, key, TWO_SHIRTS, "alice@example.com", lab.clock.instant())
                        .withState(IntentState.QUOTING, lab.clock.instant()))));

        ProposalResult result = lab.propose(loser, TWO_SHIRTS);

        assertThat(result.intentId()).isEqualTo(winner);

        Lab other = new Lab();
        Lab.Worker nobody = other.worker("worker-c", other.merchant, other.approvals,
                (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                        new Class<?>[] {IntentRepository.class}, (proxy, m, args) -> {
                            if (m.getName().equals("insert")) {
                                throw new DuplicateIntentException("no winner");
                            }
                            try {
                                return m.invoke(other.intents, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        }));
        assertThatThrownBy(() -> other.propose(nobody, TWO_SHIRTS)).isInstanceOf(DuplicateIntentException.class);
    }

    // Covers: 11, 9.1. Kills DefaultOrchestrator.propose:290 (lambda$propose$3 EmptyObjectReturnVals), :291 ConditionalsBoundary /
    // RemoveConditional_ORDER_ELSE (the rationale is cut at 300 characters, and not before), :292 event, :293 policyEvent and
    // :294 transitionEvent: a proposal is recorded with its lines and rationale, the policy decision and the transition
    @Test
    void aProposal_isRecorded_withItsLinesItsRationaleAndItsDecision() {
        String exact = "x".repeat(300);
        ProposalResult first = w.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, exact);
        ProposalResult second = w.orchestrator().propose(lab.alice(), SHOP, List.of(new ProposedLine("item_456", 1)), exact + "y");

        assertThat(events(first.intentId(), "proposed")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"lines\":[{\"item_id\":\"item_123\",\"quantity\":2}]").contains("\"merchant_id\":\"shop-1\"")
                .contains("\"rationale\":\"" + exact + "\""));
        assertThat(events(second.intentId(), "proposed")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"rationale\":\"" + exact + "\"").doesNotContain(exact + "y"));
        assertThat(events(first.intentId(), "policy_decision")).anySatisfy(e -> assertThat(e)
                .contains("\"stage\":\"proposal\"").contains("\"allowed\":true"));
        assertThat(events(first.intentId(), "transition")).anySatisfy(e -> assertThat(e)
                .contains("\"from\":\"PROPOSED\"").contains("\"event\":\"POLICY_OK\"").contains("\"to\":\"QUOTING\""));
    }

    // Covers: 9.1 P4, 7.2. Kills DefaultOrchestrator.propose:295 RemoveConditional_EQUAL_ELSE and :302 RemoveConditional_EQUAL_IF
    // (a proposal that policy denies ends REJECTED at once, with the rule as its reason, no quote and no merchant call;
    // an allowed one that reaches approval carries its quote, one that does not carries none)
    @Test
    void aDeniedProposal_isRejectedWithNoQuoteAndNoCall_andAnAllowedOneCarriesItsQuote() {
        List<ProposedLine> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new ProposedLine("item_" + i, 1));
        }

        ProposalResult denied = w.orchestrator().propose(lab.alice(), SHOP, six, "too many lines");

        assertThat(denied.state()).isEqualTo(IntentState.REJECTED);
        assertThat(denied.quote()).isEmpty();
        assertThat(lab.intent(denied.intentId()).failureReason()).contains("policy_P4");
        assertThat(lab.merchant.log()).isEmpty();
        assertThat(events(denied.intentId(), "policy_decision")).anySatisfy(e -> assertThat(e)
                .contains("\"stage\":\"proposal\"").contains("\"allowed\":false").contains("\"rule\":\"P4\""));
        assertThat(events(denied.intentId(), "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"POLICY_DENIED\""));

        ProposalResult allowed = w.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "two shirts");
        assertThat(allowed.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(allowed.quote()).hasValueSatisfying(q -> assertThat(q.totalMinor()).isEqualTo(5400));

        ProposalResult unavailable = w.orchestrator().propose(lab.alice(), SHOP, List.of(new ProposedLine("no_such_item", 1)), "x");
        assertThat(unavailable.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(unavailable.quote()).isEmpty();
    }

    // Covers: 7.3. Kills DefaultOrchestrator.lambda$newAttempt$43:1290 (the stored hash of each body, here of the update)
    @Test
    void everyAttempt_storesTheHashOfTheBodyItSent() {
        ProposalResult p = lab.propose(w, TWO_SHIRTS);

        MerchantAttempt create = attempts(p.intentId(), AttemptOp.CREATE).get(0);

        assertThat(create.requestHash()).contains(dev.ucplab.core.crypto.Sha256.hex(create.requestBody().orElseThrow().json()));
    }

    // ---------------------------------------------------------------- the time the caller waits

    private static final OrchestratorConfig NO_JITTER = new OrchestratorConfig(Duration.ofSeconds(30), Duration.ofSeconds(10),
            Duration.ofSeconds(20), Duration.ofSeconds(60), 2, Duration.ofMillis(250), Duration.ofSeconds(2), 0.0);

    /** An intent in RECONCILING whose every read says a complete is in progress, so every step polls again. */
    private IntentId polling(OrchestratorConfig config) {
        lab = new Lab(config);
        RewritingGateway gateway = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                as(CheckoutStatus.COMPLETE_IN_PROGRESS, false));
        w = lab.worker("worker-a", gateway);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        return id;
    }

    // Covers: 7.5 "Who waits". Kills DefaultOrchestrator.nextAttemptAt:326 RemoveConditional_ORDER_IF, :327 RemoveConditional_ORDER_IF,
    // :335, :337 (the delay of the next poll): from the first poll it is the initial delay, then as long as the time
    // since the first uncertainty, up to the cap, and never past the deadline
    @Test
    void thePollDelay_growsWithTheTimeSinceTheFirstUncertainty_isCapped_andStopsAtTheDeadline() {
        IntentId id = polling(NO_JITTER);
        Instant t0 = lab.clock.instant();

        StepResult first = w.orchestrator().advance(id);
        assertThat(first.nextAttemptAt()).contains(t0.plusMillis(250));

        lab.clock.advance(Duration.ofSeconds(1));
        assertThat(w.orchestrator().advance(id).nextAttemptAt()).as("1 s since").contains(t0.plusSeconds(1).plusSeconds(1));

        lab.clock.advance(Duration.ofSeconds(4));
        assertThat(w.orchestrator().advance(id).nextAttemptAt()).as("capped at 2 s").contains(t0.plusSeconds(5).plusSeconds(2));

        lab.clock.advance(Duration.ofSeconds(54));
        assertThat(w.orchestrator().advance(id).nextAttemptAt()).as("59 s since: the deadline, 1 s on")
                .contains(t0.plusSeconds(60));
    }

    // Covers: 7.5. Kills DefaultOrchestrator.nextAttemptAt:331 Math and :335 Math (jitter, with a known random source): the
    // delay is the base times 1 + jitter * (2r - 1) for the orchestrator's own random draw
    @Test
    void thePollDelay_carriesTheConfiguredJitter() {
        OrchestratorConfig jittery = new OrchestratorConfig(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(60), 2, Duration.ofMillis(250), Duration.ofSeconds(2), 0.5);
        IntentId id = polling(jittery);
        Instant t0 = lab.clock.instant();
        double r = new SplittableRandom(42).nextDouble();
        long expected = Math.round(Duration.ofMillis(250).toNanos() * (1 + 0.5 * (2 * r - 1)));

        StepResult first = w.orchestrator().advance(id);

        assertThat(first.nextAttemptAt()).contains(t0.plusNanos(expected));
    }

    // Covers: 7.5, 7.8. Kills DefaultOrchestrator.nextAttemptAt:333 VoidMethodCall (the jitter lock is released): two threads
    // advancing polling intents do not wait for each other for ever
    @Test
    void theJitterLock_isReleased() throws Exception {
        IntentId id = polling(NO_JITTER);
        w.orchestrator().advance(id);
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        try {
            assertThat(other.submit(() -> w.orchestrator().advance(id).nextAttemptAt()).get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isPresent();
        } finally {
            other.shutdownNow();
        }
    }

    // Covers: 7.3. Kills DefaultOrchestrator.lambda$nextAttemptAt$4:319 NullReturnVals (NO_COVERAGE): an intent that disappears
    // while its step is being answered is reported as not found, not as a result with no time
    @Test
    void anIntentThatVanishesBeforeTheNextAttemptIsKnown_isNotFound() {
        IntentRepository vanishing = (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, m, args) -> {
                    if (m.getName().equals("findForSystem")) {
                        return Optional.empty();
                    }
                    try {
                        return m.invoke(lab.intents, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        Lab.Worker blind = lab.worker("worker-b", lab.merchant, lab.approvals, vanishing);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        lab.decide(w, id, true);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThatThrownBy(() -> blind.orchestrator().advance(id)).isInstanceOf(dev.ucplab.core.intent.IntentNotFoundException.class);
    }

    // ---------------------------------------------------------------- gateway and instrument

    private final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

    private String logged() {
        SimpleFormatter f = new SimpleFormatter();
        StringBuilder out = new StringBuilder();
        for (LogRecord r : records) {
            out.append(r.getLevel()).append(' ').append(f.formatMessage(r)).append('\n');
        }
        return out.toString();
    }

    private void captureLog() {
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger log = Logger.getLogger(DefaultOrchestrator.class.getName());
        log.addHandler(capture);
        log.setLevel(Level.ALL);
    }

    // Covers: 8.5, 19, I11. Kills DefaultOrchestrator.gateway:409 and :413 VoidMethodCall (the log line), :412
    // RemoveConditional_EQUAL_ELSE: a merchant with no gateway, whether the lookup refuses it or answers nothing, sends nothing
    // and changes nothing, and says so in the log, naming the merchant
    @Test
    void aMerchantWithNoGateway_changesNothing_andIsLogged() {
        captureLog();
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        PurchaseIntent before = lab.intent(id);
        Function<MerchantId, MerchantGateway> refusing = m -> {
            throw new NoGatewayException(m);
        };
        Function<MerchantId, MerchantGateway> silent = m -> null;

        assertThatThrownBy(() -> lab.routedWorker("worker-x", refusing).orchestrator().advance(id)).isInstanceOf(NoGatewayException.class);
        assertThat(logged()).contains("SEVERE no gateway for merchant shop-1");
        records.clear();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThatThrownBy(() -> lab.routedWorker("worker-y", silent).orchestrator().advance(id)).isInstanceOf(NoGatewayException.class);
        assertThat(logged()).contains("SEVERE no gateway for merchant shop-1");

        assertThat(lab.intent(id).state()).isEqualTo(before.state());
        assertThat(attempts(id, AttemptOp.COMPLETE)).isEmpty();
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
    }

    // Covers: 19, I11. Kills DefaultOrchestrator.instrument:425 and :429 VoidMethodCall, :428 RemoveConditional_EQUAL_ELSE: a
    // merchant with no payment instrument is never paid with another's: nothing is sent, and the log names the merchant
    @Test
    void aMerchantWithNoInstrument_sendsNoComplete_andIsLogged() {
        captureLog();
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Function<MerchantId, MerchantGateway> gateways = m -> lab.merchant;
        Function<MerchantId, PaymentInstrument> refusing = m -> {
            throw new NoInstrumentException(m);
        };
        Function<MerchantId, PaymentInstrument> silent = m -> null;

        assertThatThrownBy(() -> lab.routedWorker("worker-x", gateways, refusing).orchestrator().advance(id))
                .isInstanceOf(NoInstrumentException.class);
        assertThat(logged()).contains("SEVERE no payment instrument for merchant shop-1");
        records.clear();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThatThrownBy(() -> lab.routedWorker("worker-y", gateways, silent).orchestrator().advance(id))
                .isInstanceOf(NoInstrumentException.class);
        assertThat(logged()).contains("SEVERE no payment instrument for merchant shop-1");
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
    }

    // ---------------------------------------------------------------- the quote cycle

    private MerchantAttempt createAttempt(IntentId id, String attemptId, AttemptStatus status, Optional<String> response,
            AttemptOp op, int revision) {
        Optional<IdempotencyKey> key = op == AttemptOp.CREATE && revision == 0
                ? Optional.of(IdempotencyKey.derive(id, MutatingOp.CREATE, 0)) : Optional.empty();
        return new MerchantAttempt(new AttemptId(attemptId), id, op, revision, key, Optional.empty(),
                Optional.of(new FrozenBody("{}")), status, Optional.empty(), response, lab.clock.instant(), Optional.empty());
    }

    /** A QUOTING intent with no session whose stored attempts are {@code attempts}, found by a worker after a lapsed lease. */
    private IntentId quotingWith(String name, MerchantAttempt... attempts) {
        IntentId id = new IntentId(name);
        lab.intents.insert(PurchaseIntent.proposed(id, ALICE, SHOP, "dedupe-" + name, TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.QUOTING, lab.clock.instant())
                .withLease(Optional.of("setup"), Optional.of(lab.clock.instant())));
        long version = 0;
        for (MerchantAttempt a : attempts) {
            lab.intents.recordAttempt(a, lab.intent(id), version++, "setup");
        }
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return id;
    }

    private MerchantAttempt forId(MerchantAttempt a, IntentId id) {
        return new MerchantAttempt(a.id(), id, a.op(), a.revision(), a.idempotencyKey(), a.requestHash(), a.requestBody(),
                a.status(), a.httpStatus(), a.responseJson(), a.sentAt(), a.respondedAt());
    }

    // Covers: 7.2, 7.6 M3 note, 8.1. Kills DefaultOrchestrator.quote:452 to :460, lambda$quote$10:456 (BooleanTrue and the three
    // EQUAL_IF), mayHaveCreatedSession:682 BooleanTrue and lambda$mayHaveCreatedSession$19:685 (NO_COVERAGE),
    // lambda$quoteCycle$13:500: a quote step never repeats a call already made in its cycle: it ends the intent as exhausted,
    // records the transition, and owes a cancel exactly when a create may have left a session the intent does not know (still
    // SENT, or uncertain after an answer that was not NotSent), and not for one that was answered, not sent, or is not a create
    @Test
    void aQuoteCycleThatAlreadyCalled_isExhausted_andOwesACancelOnlyForAPossibleOrphan() {
        record Case(String name, MerchantAttempt[] attempts, boolean owed) {}
        IntentId id = new IntentId("x");
        List<Case> cases = List.of(
                new Case("sent", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.SENT, Optional.empty(), AttemptOp.CREATE, 0)}, true),
                new Case("uncertain", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.UNCERTAIN,
                        Optional.of("{\"outcome\":\"uncertain\"}"), AttemptOp.CREATE, 0)}, true),
                new Case("uncertain-no-response", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.UNCERTAIN,
                        Optional.empty(), AttemptOp.CREATE, 0)}, true),
                new Case("not-sent", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.UNCERTAIN,
                        Optional.of("{\"outcome\":\"not_sent\"}"), AttemptOp.CREATE, 0)}, false),
                new Case("answered", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.RESPONDED,
                        Optional.of("{\"outcome\":\"ok\"}"), AttemptOp.CREATE, 0)}, false),
                new Case("update-left-sent", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.RESPONDED,
                        Optional.of("{\"outcome\":\"ok\"}"), AttemptOp.CREATE, 0),
                        createAttempt(id, "b", AttemptStatus.SENT, Optional.empty(), AttemptOp.UPDATE, 0)}, false),
                new Case("other-revision", new MerchantAttempt[] {createAttempt(id, "a", AttemptStatus.RESPONDED,
                        Optional.of("{\"outcome\":\"ok\"}"), AttemptOp.CREATE, 0),
                        createAttempt(id, "b", AttemptStatus.SENT, Optional.empty(), AttemptOp.CREATE, 1)}, false));
        for (Case c : cases) {
            lab = new Lab();
            w = lab.worker("worker-a");
            IntentId made = quotingWith("q-" + c.name(), java.util.Arrays.stream(c.attempts()).map(a -> forId(a, new IntentId("q-" + c.name()))).toArray(MerchantAttempt[]::new));

            w.orchestrator().advance(made);

            PurchaseIntent after = lab.intent(made);
            assertThat(after.state()).as(c.name()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(after.failureReason()).as(c.name()).contains("quote_retries_exhausted");
            assertThat(after.cancelPending()).as(c.name()).isEqualTo(c.owed());
            assertThat(lab.merchant.log()).as(c.name() + ": nothing is sent again").isEmpty();
            assertThat(events(made, "transition")).as(c.name()).anySatisfy(e -> assertThat(e)
                    .contains("\"from\":\"QUOTING\"").contains("\"event\":\"RETRIES_EXHAUSTED\"").contains("\"to\":\"FAILED_NOT_CHARGED\""));
        }
    }

    // Covers: 7.4, 7.2. Kills DefaultOrchestrator.quote:448 RemoveConditional_EQUAL_ELSE: each re-quote is an update under its
    // own key, numbered from zero: the revisions of the updates after two price changes are 0 and 1, and their keys derive
    // from them
    @Test
    void eachRequote_isAnUpdateUnderItsOwnNumberedKey() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.PriceChange("item_123", 2700)));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        for (int i = 0; i < 2; i++) {
            assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
            assertThat(lab.drive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        }

        assertThat(attempts(id, AttemptOp.UPDATE)).extracting(MerchantAttempt::revision).containsExactly(0, 1);
        assertThat(attempts(id, AttemptOp.UPDATE)).extracting(a -> a.idempotencyKey().orElseThrow())
                .containsExactly(IdempotencyKey.derive(id, MutatingOp.UPDATE, 0), IdempotencyKey.derive(id, MutatingOp.UPDATE, 1));
    }

    // Covers: 7.4, 8.4. Kills DefaultOrchestrator.lambda$requireSession$46:1324 NullReturnVals (NO_COVERAGE): a re-quote of an
    // intent that has no session is a corrupt row, refused loudly
    @Test
    void aRequoteWithNoSession_isRefused() {
        IntentId id = new IntentId("nosession");
        lab.intents.insert(PurchaseIntent.proposed(id, ALICE, SHOP, "dedupe-ns", TWO_SHIRTS, "alice@example.com", lab.clock.instant())
                .withState(IntentState.QUOTING, lab.clock.instant()).withRequoteCount(1));

        assertThatThrownBy(() -> w.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no session");
        assertThat(lab.merchant.log()).isEmpty();
    }

    // ---------------------------------------------------------------- the shipping selection

    private IntentId proposeAtAFulfillmentMerchant(Lab l, Lab.Worker worker) {
        l.merchant.enableFulfillment();
        return l.propose(worker, TWO_SHIRTS).intentId();
    }

    // Covers: 7.2 shipping selection, 8.4. Kills DefaultOrchestrator.onQuoteOutcome:632 event, lambda$pendingSelection$14:534 and
    // selectionStep: each step that still needs a selection says so, the updates are numbered 0, 1, 2 under their own keys and
    // carry the line ids of the answer, and the re-quote count stays 0
    @Test
    void theSelectionSteps_areRecorded_numbered_andCarryTheLineIds() {
        lab = new Lab();
        w = lab.worker("worker-a");
        IntentId id = proposeAtAFulfillmentMerchant(lab, w);
        for (int i = 0; i < 6 && lab.intent(id).state() == IntentState.QUOTING; i++) {
            w.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intent(id).requoteCount()).isZero();
        assertThat(events(id, "fulfillment_selection_needed")).hasSize(3)
                .allSatisfy(e -> assertThat(e).contains("\"status\":\"incomplete\""));
        assertThat(attempts(id, AttemptOp.UPDATE)).extracting(MerchantAttempt::revision).containsExactly(0, 1, 2);
        assertThat(attempts(id, AttemptOp.UPDATE).get(0).requestBody().orElseThrow().json()).contains("\"line_item_ids\":[\"li_1\"]");
    }

    private static java.util.function.UnaryOperator<CallOutcome<CheckoutView>> neverSelected() {
        return outcome -> {
            if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
                return outcome;
            }
            CheckoutView v = ok.value();
            List<String> lineIds = v.lines().stream().flatMap(l -> l.lineId().stream()).toList();
            FulfillmentView stuck = new FulfillmentView(List.of(new FulfillmentView.Method("method_1", "shipping", lineIds,
                    List.of(new FulfillmentView.Destination("addr_1", Optional.of("US"))), Optional.empty(), List.of())));
            CheckoutView answer = new CheckoutView(v.id(), CheckoutStatus.INCOMPLETE, v.currency(), v.lines(), v.subtotalMinor(),
                    v.taxMinor(), v.totalMinor(), Optional.empty(), v.buyer(), List.of(), Optional.of(stuck), 0, true);
            return new CallOutcome.Ok<>(ok.httpStatus(), answer, List.of());
        };
    }

    // Covers: 7.2 shipping selection. Kills DefaultOrchestrator.selectionStep:556 (BooleanTrue, EQUAL_IF, EQUAL_ELSE), :557
    // ConditionalsBoundary / RemoveConditional_ORDER_ELSE, :560 update, :561 transitionEvent, :562 return: a merchant that never
    // gets ready is sent exactly four selection updates, then the quote ends exhausted, with the transition recorded
    @Test
    void aMerchantThatNeverGetsReady_isSentFourSelectionUpdates_thenExhausted() {
        lab = new Lab();
        RewritingGateway stuck = new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, neverSelected())
                .rewrite(MerchantCall.CREATE, neverSelected());
        w = lab.worker("worker-a", stuck);
        IntentId id = proposeAtAFulfillmentMerchant(lab, w);
        for (int i = 0; i < 12 && lab.intent(id).state() == IntentState.QUOTING; i++) {
            w.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(attempts(id, AttemptOp.UPDATE)).hasSize(4);
        assertThat(events(id, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"RETRIES_EXHAUSTED\""));
        assertThat(lab.intent(id).cancelPending()).as("the session is cancelled, best effort").isTrue();
    }

    // Covers: 7.2 shipping selection, 7.6. Kills DefaultOrchestrator.pendingSelection:513 RemoveConditional_EQUAL_ELSE / EQUAL_IF:
    // a selection update that was left SENT by a crash is not repeated: the next worker ends the quote as exhausted
    @Test
    void aSelectionUpdateLeftSent_isNotRepeated() {
        lab = new Lab();
        InterceptingGateway dying = new InterceptingGateway(lab.merchant);
        w = lab.worker("worker-a", dying);
        IntentId id = proposeAtAFulfillmentMerchant(lab, w);
        dying.crashBeforeNext(MerchantCall.UPDATE);
        assertThatThrownBy(() -> w.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(attempts(id, AttemptOp.UPDATE)).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.SENT));
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        lab.worker("worker-b").orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(lab.merchant.delivered(MerchantCall.UPDATE)).isZero();
    }

    // Covers: 7.2, 8.1. Kills DefaultOrchestrator.onQuoteOutcome:628 (all three EQUAL_IF), selectionPending:546 (EQUAL_ELSE/IF),
    // :547 BooleanTrue (NO_COVERAGE), :549 BooleanTrue and pendingSelection:523 (the answer is incomplete with no message, the
    // merchant offers fulfillment, and something is left to select): any other incomplete answer is not a selection step: it
    // ends the quote as exhausted at once
    @Test
    void anIncompleteAnswer_isASelectionStepOnlyWhenNothingElseIsWrongAndSomethingIsLeftToSelect() {
        // an error message on the answer
        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, error("missing"))));
        lab.merchant.enableFulfillment();
        IntentId withMessage = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(withMessage).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(withMessage).failureReason()).contains("quote_retries_exhausted");

        // fulfillment not offered
        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false)));
        IntentId notOffered = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(notOffered).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(notOffered).failureReason()).contains("quote_retries_exhausted");
        assertThat(events(notOffered, "fulfillment_selection_needed")).isEmpty();

        // everything selected and still incomplete: nothing left to select
        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE,
                as(CheckoutStatus.INCOMPLETE, false)));
        IntentId allSelected = proposeAtAFulfillmentMerchant(lab, w);
        for (int i = 0; i < 8 && lab.intent(allSelected).state() == IntentState.QUOTING; i++) {
            w.orchestrator().advance(allSelected);
        }
        assertThat(lab.intent(allSelected).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(allSelected).failureReason()).contains("quote_retries_exhausted");
        assertThat(attempts(allSelected, AttemptOp.UPDATE)).hasSize(3);
    }

    // Covers: 9.2, 7.2. Kills DefaultOrchestrator.lambda$unsupportedFulfillment$16:539 RemoveConditional_ORDER_IF, onQuoteOutcome:603
    // area: a merchant that lists more than one group is not supported (the snapshot names one): the quote ends exhausted and
    // an event says why
    @Test
    void moreThanOneGroup_isUnsupported_andSaidSo() {
        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.setGroupCount(2);
        IntentId id = proposeAtAFulfillmentMerchant(lab, w);
        for (int i = 0; i < 8 && lab.intent(id).state() == IntentState.QUOTING; i++) {
            w.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(events(id, "fulfillment_unsupported")).singleElement()
                .satisfies(e -> assertThat(e).contains("more than one method or group"));
    }

    // ---------------------------------------------------------------- what each answer to a create or an update leads to

    // Covers: 4 I4, 7.2. Kills DefaultOrchestrator.onQuoteOutcome:582 RemoveConditional_EQUAL_ELSE (both): an update answered for
    // another session than the intent's is refused: nothing is stored as the answer
    @Test
    void anUpdateAnsweredForAnotherSession_isRefused() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        RewritingGateway wrong = new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, outcome -> {
            CallOutcome.Ok<CheckoutView> ok = (CallOutcome.Ok<CheckoutView>) outcome;
            CheckoutView v = ok.value();
            CheckoutView other = new CheckoutView(new dev.ucplab.core.merchant.SessionId("cs_other"), v.status(), v.currency(),
                    v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(), v.order(), v.buyer(), v.messages());
            return new CallOutcome.Ok<>(ok.httpStatus(), other, v.messages());
        });
        w = lab.worker("worker-a", wrong);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);

        assertThatThrownBy(() -> w.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("I4: update answered for another session");
        assertThat(attempts(id, AttemptOp.UPDATE)).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.SENT));
    }

    // Covers: I4, I8, H5 C1 (the refusal of an update answered for another session names that session masked: a
    // checkout id's ?key= never reaches an exception message)
    @Test
    void anUpdateAnsweredForAnotherKeyedSession_isRefusedWithTheKeyMasked() {
        String canary = "CANARY-key-81b4f0";
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        RewritingGateway wrong = new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, outcome -> {
            CallOutcome.Ok<CheckoutView> ok = (CallOutcome.Ok<CheckoutView>) outcome;
            CheckoutView v = ok.value();
            CheckoutView other = new CheckoutView(new dev.ucplab.core.merchant.SessionId("cs_other?key=" + canary),
                    v.status(), v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(), v.order(),
                    v.buyer(), v.messages());
            return new CallOutcome.Ok<>(ok.httpStatus(), other, v.messages());
        });
        w = lab.worker("worker-a", wrong);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        w.orchestrator().advance(id);

        assertThatThrownBy(() -> w.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cs_other?[redacted]").message().doesNotContain(canary);
    }

    // Covers: 7.2, 8.1. Kills DefaultOrchestrator.onQuoteOutcome:609 and :613 RemoveConditional_EQUAL_ELSE and finish:744
    // transitionEvent: a create answered with an out_of_stock or item_unavailable error ends FAILED_NOT_CHARGED with that
    // reason and its own transition event
    @Test
    void aCreateAnsweredWithAStockError_endsNotCharged_withThatReason() {
        for (String code : List.of("out_of_stock", "item_unavailable")) {
            lab = new Lab();
            w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                    as(CheckoutStatus.INCOMPLETE, false, error(code))));

            IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

            assertThat(lab.intent(id).state()).as(code).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(lab.intent(id).failureReason()).as(code).contains(code);
            assertThat(events(id, "transition")).as(code).anySatisfy(e -> assertThat(e)
                    .contains("\"event\":\"" + code.toUpperCase() + "\""));
        }
    }

    // Covers: 8.1, 7.2. Kills DefaultOrchestrator.onQuoteOutcome:638 and :643 and rejectedReason:1353 / :1354 (NO_COVERAGE): a
    // Rejected create ends the intent with the code the merchant gave (or create_rejected); a Rejected answer to an update
    // cannot settle anything, so it ends as exhausted
    @Test
    void aRejectedAnswer_endsACreateWithItsCode_andAnUpdateAsExhausted() {
        for (String code : List.of("out_of_stock", "item_unavailable", "something_else")) {
            lab = new Lab();
            w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                    RewritingGateway.always(new CallOutcome.Rejected<CheckoutView>(200, List.of(error(code))))));

            IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

            assertThat(lab.intent(id).state()).as(code).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(lab.intent(id).failureReason()).as(code).contains(code.equals("something_else") ? "create_rejected" : code);
            assertThat(attempts(id, AttemptOp.CREATE)).as(code).singleElement().satisfies(a -> assertThat(a.responseJson().orElseThrow())
                    .contains("\"outcome\":\"rejected\"").contains("\"codes\":[\"" + code + "\"]"));
        }

        lab = new Lab();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE,
                RewritingGateway.always(new CallOutcome.Rejected<CheckoutView>(200, List.of(error("out_of_stock"))))));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        w.orchestrator().advance(id);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
    }

    // Covers: 8.1, 8.2, 7.2. Kills DefaultOrchestrator.onQuoteOutcome:645, :650, :656 (NO_COVERAGE) and isRetryable:1358: a
    // protocol error on a create ends the intent: one that asks to be retried (Retry-After, 429, 503) as exhausted; one that is
    // out_of_stock or item_unavailable with that reason; any other with merchant_error_ and its code
    @Test
    void aProtocolErrorOnCreate_endsTheIntent_byWhetherItAsksToBeRetried() {
        record Case(Fault.Reject fault, String reason) {}
        for (Case c : List.of(
                new Case(new Fault.Reject(429, "slow", Optional.empty()), "quote_retries_exhausted"),
                new Case(new Fault.Reject(503, "busy", Optional.empty()), "quote_retries_exhausted"),
                new Case(new Fault.Reject(400, "later", Optional.of(Duration.ofSeconds(3))), "quote_retries_exhausted"),
                new Case(new Fault.Reject(400, "out_of_stock", Optional.empty()), "out_of_stock"),
                new Case(new Fault.Reject(400, "item_unavailable", Optional.empty()), "item_unavailable"),
                new Case(new Fault.Reject(400, "bad_thing", Optional.empty()), "merchant_error_bad_thing"))) {
            lab = new Lab();
            w = lab.worker("worker-a");
            lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, c.fault()));

            IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

            assertThat(lab.intent(id).state()).as(c.toString()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(lab.intent(id).failureReason()).as(c.toString()).contains(c.reason());
            assertThat(attempts(id, AttemptOp.CREATE)).as(c.toString()).singleElement().satisfies(a -> {
                assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED);
                assertThat(a.httpStatus()).contains(c.fault().httpStatus());
                assertThat(a.responseJson().orElseThrow()).contains("\"outcome\":\"protocol_error\"");
            });
        }
    }

    // Covers: 8.1, 7.6 M3 note. Kills DefaultOrchestrator.onQuoteOutcome:665 and :667 (both EQUAL_IF) and kind:1366, describe:1391:
    // a create that was not sent ends exhausted and owes nothing; one whose answer was lost ends exhausted and owes a cancel
    // (it may have made a session); an update whose answer was lost, with the session known, owes the cancel through the
    // table; each stores what happened
    @Test
    void aNotSentOrLostCreate_endsExhausted_andOnlyTheLostOneOwesACancel() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.NotSent()));
        IntentId notSent = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(notSent).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(notSent).failureReason()).contains("quote_retries_exhausted");
        assertThat(lab.intent(notSent).cancelPending()).isFalse();
        assertThat(attempts(notSent, AttemptOp.CREATE)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN);
            assertThat(a.responseJson().orElseThrow()).contains("\"outcome\":\"not_sent\"");
        });

        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        IntentId lost = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(lost).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(lost).cancelPending()).isTrue();
        assertThat(attempts(lost, AttemptOp.CREATE)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN);
            assertThat(a.responseJson().orElseThrow()).contains("\"outcome\":\"uncertain\"");
        });

        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.UPDATE, 1, new Fault.CommitThenDrop()));
        IntentId requote = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, requote, true);
        w.orchestrator().advance(requote);
        w.orchestrator().advance(requote);
        assertThat(lab.intent(requote).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(requote).cancelPending()).isTrue();
    }

    // Covers: 9.1 P2, 7.2, 11. Kills DefaultOrchestrator.quoteReady:697 and :712 policyEvent (VoidMethodCall) and policyEvent:1428
    // (Optional.ifPresent of the rule): the policy decision on a quote is recorded, allowed or denied, with the rule that
    // denied it; a denied quote ends FAILED_NOT_CHARGED with that rule as its reason
    @Test
    void theDecisionOnAQuote_isRecorded_allowedOrDenied() {
        ProposalResult allowed = lab.propose(w, TWO_SHIRTS);
        assertThat(events(allowed.intentId(), "policy_decision")).anySatisfy(e -> assertThat(e)
                .contains("\"stage\":\"quote\"").contains("\"allowed\":true").doesNotContain("\"rule\""));

        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.setPrice("item_123", 30000);
        ProposalResult denied = lab.propose(w, TWO_SHIRTS);

        assertThat(denied.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(denied.intentId()).failureReason()).contains("policy_P2");
        assertThat(events(denied.intentId(), "policy_decision")).anySatisfy(e -> assertThat(e)
                .contains("\"stage\":\"quote\"").contains("\"allowed\":false").contains("\"rule\":\"P2\""));
        assertThat(lab.intent(denied.intentId()).cancelPending()).isTrue();
    }
}
