package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.HandoffWatch;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StoreOrder;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.OrderRef;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The handoff follow-up (7.6, VERIFY 7): after the move to HANDED_OFF the worker reads the checkout on a schedule, one
 * {@code get} per step, and records the order the store reports as the store's. The intent stays HANDED_OFF, nothing
 * mutating is ever sent, and the follow-up ends at an order, canceled, a 404, a changed entry, the read cap or the window.
 */
class HandoffFollowUpTest {

    static final String URL = HandoffTest.URL;
    static final String ORDER = "gid://shopify/Order/42";
    static final String LABEL = "CANARYLABEL7";

    protected final Lab lab = newLab();
    FollowUpGateway store;
    Lab.Worker worker;

    /** A lab over the store under test: the in-memory adapters here, SQLite in {@code SqlHandoffFollowUpTest}. */
    protected Lab newLab() {
        return new Lab();
    }

    HandoffFollowUpTest() {
        lab.entries = id -> id.equals(SHOP) ? Optional.of(HandoffTest.HANDOFF) : Optional.empty();
        store = new FollowUpGateway(new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, HandoffTest.withUrl(URL)),
                lab.clock);
        worker = lab.worker("w", store);
    }

    /** Proposed, approved and handed off; returns the intent, with the clock at the moment of the handoff. */
    private IntentId handedOff() {
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        lab.decide(worker, id, true);
        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(IntentState.HANDED_OFF);
        return id;
    }

    /** Advances the clock by {@code by}, then steps the intent until idle, as one worker pass would. */
    private void pass(IntentId id, Duration by) {
        lab.clock.advance(by);
        for (int i = 0; i < 5 && !worker.orchestrator().advance(id).idle(); i++) {
            // a step that read: the next advance finds nothing due and is idle
        }
    }

    /** Passes every 30 s for {@code total}. */
    private void run(IntentId id, Duration total) {
        for (Duration t = Duration.ZERO; t.compareTo(total) < 0; t = t.plusSeconds(30)) {
            pass(id, Duration.ofSeconds(30));
        }
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> followUpReads(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.GET)
                .filter(a -> a.responseJson().orElse("").contains("\"resolved_by\":\"handoff_follow_up\"")).toList();
    }

    static CheckoutView with(CheckoutView v, CheckoutStatus status, Optional<OrderRef> order, Optional<Buyer> buyer) {
        return new CheckoutView(v.id(), status, v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(),
                order, buyer, v.messages(), v.fulfillment(), v.fulfillmentMinor(), v.fulfillmentOffered());
    }

    static UnaryOperator<CallOutcome<CheckoutView>> answer(CheckoutStatus status) {
        return o -> o instanceof CallOutcome.Ok<CheckoutView> ok
                ? new CallOutcome.Ok<>(ok.httpStatus(), with(ok.value(), status, Optional.empty(), Optional.empty()), List.of())
                : o;
    }

    /** What VERIFY 7 recorded: completed, with an order and its label, and the buyer the store's page collected. */
    static UnaryOperator<CallOutcome<CheckoutView>> placed() {
        Buyer buyer = new Buyer(Optional.of("canary-buyer@example.com"), Optional.of("Canaryfirst"),
                Optional.of("Canarylast"), Optional.of("+15550000000"));
        return o -> o instanceof CallOutcome.Ok<CheckoutView> ok
                ? new CallOutcome.Ok<>(ok.httpStatus(), with(ok.value(), CheckoutStatus.COMPLETED,
                        Optional.of(new OrderRef(ORDER, Optional.of(LABEL))), Optional.of(buyer)), List.of())
                : o;
    }

    static UnaryOperator<CallOutcome<CheckoutView>> always(CallOutcome<CheckoutView> outcome) {
        return o -> outcome;
    }

    // Covers: 7.2 hand_off (the move to HANDED_OFF opens the follow-up in the same write: the window ends 24 h after the
    // handoff, and the first read is due a minute after it), 7.6, 10
    @Test
    void theHandoff_opensTheWindow() {
        IntentId id = handedOff();
        Instant t = lab.clock.instant();

        HandoffWatch watch = lab.intent(id).handoffWatch();
        assertThat(watch.until()).contains(t.plus(Duration.ofHours(24)));
        assertThat(watch.nextReadAt()).contains(t.plus(Duration.ofMinutes(1)));
        assertThat(watch.storeOrder()).isEmpty();
    }

    // Covers: 7.6 handoff follow-up, FR5, FR8, I1 (the store places the order before the third read: the order is the
    // store's, not the agent's order_id; the window closes; the intent stays HANDED_OFF; one event with the order id)
    @Test
    void anOrderPlacedAtTheStore_isSeen_andTheIntentStaysHandedOff() {
        IntentId id = handedOff();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE), answer(CheckoutStatus.INCOMPLETE), placed());

        run(id, Duration.ofMinutes(10));

        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.state()).isEqualTo(IntentState.HANDED_OFF);
        assertThat(intent.orderId()).as("the agent completed no order").isEmpty();
        assertThat(intent.handoffWatch().storeOrder()).contains(new StoreOrder(ORDER, Optional.of(LABEL)));
        assertThat(intent.handoffWatch().watching()).isFalse();
        assertThat(intent.handoffWatch().nextReadAt()).isEmpty();
        assertThat(store.reads).hasSize(3);
        assertThat(events(id, "handoff_order_seen")).containsExactly("{\"order_id\":\"" + ORDER + "\",\"reads\":3}");
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.6 (the schedule: 1, 2, 4, 8, 16 and 30 minutes after the handoff, then every 30 minutes; the window of
    // 24 h binds: 52 reads, the last at 23 h 30, and the step at 24 h ends it with window_ended and reads nothing), I5
    @Test
    void theReads_followTheSchedule_andTheWindowEndsItAt24h() {
        IntentId id = handedOff();
        Instant t = lab.clock.instant();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE));

        run(id, Duration.ofHours(25));

        List<Instant> expected = new ArrayList<>();
        for (long m : new long[] {1, 2, 4, 8, 16}) {
            expected.add(t.plus(Duration.ofMinutes(m)));
        }
        for (long m = 30; m < 24 * 60; m += 30) {
            expected.add(t.plus(Duration.ofMinutes(m)));
        }
        assertThat(store.reads).containsExactlyElementsOf(expected);
        assertThat(store.reads).hasSize(52);
        assertThat(events(id, "handoff_follow_up_ended")).containsExactly("{\"reads\":52,\"reason\":\"window_ended\"}");
        assertThat(lab.intent(id).handoffWatch().watching()).isFalse();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.HANDED_OFF);
    }

    // Covers: 7.6, I1, I4, I12 (over the whole window, whatever the store answers, only reads are sent: no create, update,
    // complete or cancel, and a handed-off checkout is never cancelled)
    @Test
    void onlyReadsAreSent_whateverTheStoreAnswers() {
        IntentId id = handedOff();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE), always(new CallOutcome.Uncertain<>("timeout")),
                always(new CallOutcome.ProtocolError<>(500, "server_error", "", Optional.empty())),
                always(new CallOutcome.Rejected<>(200, List.of())), answer(CheckoutStatus.COMPLETE_IN_PROGRESS),
                answer(CheckoutStatus.READY_FOR_COMPLETE), answer(CheckoutStatus.COMPLETED));

        run(id, Duration.ofHours(25));

        assertThat(store.mutatingAfterHandoff).isEmpty();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.HANDED_OFF);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intent(id).handoffWatch().storeOrder()).as("completed with no order is no order").isEmpty();
        assertThat(store.reads).hasSize(52);
    }

    // Covers: 7.6 (a canceled checkout ends the follow-up with canceled, a 404 with session_gone; neither is a failure:
    // the state, the failure reason and the cancel flag are unchanged)
    @Test
    void aCanceledCheckout_orA404_endsIt_andNeitherIsAFailure() {
        IntentId canceled = handedOff();
        store.afterHandoff(answer(CheckoutStatus.CANCELED));
        run(canceled, Duration.ofMinutes(5));

        assertThat(events(canceled, "handoff_follow_up_ended")).containsExactly("{\"reads\":1,\"reason\":\"canceled\"}");
        assertThat(store.reads).hasSize(1);

        Lab other = newLab();
        other.entries = lab.entries;
        FollowUpGateway gone = new FollowUpGateway(
                new RewritingGateway(other.merchant).rewrite(MerchantCall.GET, HandoffTest.withUrl(URL)), other.clock);
        Lab.Worker w = other.worker("w", gone);
        IntentId id = w.orchestrator().propose(other.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        other.drive(w, id);
        other.decide(w, id, true);
        w.orchestrator().advance(id);
        gone.afterHandoff(always(new CallOutcome.ProtocolError<>(404, "not_found", "", Optional.empty())));
        for (int i = 0; i < 10; i++) {
            other.clock.advance(Duration.ofSeconds(30));
            w.orchestrator().advance(id);
        }

        assertThat(other.events(id).stream().filter(e -> e.type().equals("handoff_follow_up_ended"))
                .map(IntentEvent::payloadJson)).containsExactly("{\"reads\":1,\"reason\":\"session_gone\"}");
        for (PurchaseIntent i : List.of(lab.intent(canceled), other.intent(id))) {
            assertThat(i.state()).isEqualTo(IntentState.HANDED_OFF);
            assertThat(i.failureReason()).isEmpty();
            assertThat(i.cancelPending()).isFalse();
            assertThat(i.handoffWatch().watching()).isFalse();
        }
    }

    // Covers: 7.6, 8.3 (the gateway's get bypasses the breaker, so the follow-up checks admitsCalls() itself: while it is
    // false a due step reads nothing and writes nothing, and the read happens once the gateway admits calls again)
    @Test
    void whileTheGatewayDoesNotAdmitCalls_nothingIsReadOrWritten() {
        IntentId id = handedOff();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE));
        store.admitting = false;
        long version = lab.intent(id).version();
        int events = lab.events(id).size();

        run(id, Duration.ofMinutes(10));

        assertThat(store.reads).isEmpty();
        assertThat(lab.intent(id).version()).isEqualTo(version);
        assertThat(lab.events(id)).hasSize(events);
        assertThat(followUpReads(id)).isEmpty();

        store.admitting = true;
        pass(id, Duration.ofSeconds(30));
        assertThat(store.reads).hasSize(1);
    }

    // Covers: 7.6 (a read the breaker or rate limiter refused, NotSent, reached no store: no attempt is written and it is
    // not counted; the next due read is counted as the first)
    @Test
    void aNotSentRead_isNotCounted() {
        IntentId id = handedOff();
        store.afterHandoff(always(new CallOutcome.NotSent<>("circuit_open")), placed());

        run(id, Duration.ofMinutes(5));

        assertThat(store.reads).hasSize(2);
        assertThat(followUpReads(id)).hasSize(1);
        assertThat(events(id, "handoff_order_seen")).containsExactly("{\"order_id\":\"" + ORDER + "\",\"reads\":1}");
    }

    /** Records {@code n} counted follow-up reads for the intent, as earlier steps would have. */
    private void seedReads(IntentId id, int n) {
        PurchaseIntent intent = lab.intents.claim(id, "w", Duration.ofSeconds(30)).orElseThrow();
        for (int i = 0; i < n; i++) {
            Instant at = lab.clock.instant();
            MerchantAttempt read = new MerchantAttempt(new AttemptId("seeded-" + i), id, AttemptOp.GET,
                    intent.requoteCount(), Optional.empty(), Optional.empty(), Optional.empty(), AttemptStatus.RESPONDED,
                    Optional.of(200), Optional.of("{\"resolved_by\":\"handoff_follow_up\",\"status\":\"incomplete\"}"), at,
                    Optional.of(at));
            lab.intents.recordAttempt(read, intent, intent.version(), "w");
            intent = lab.intent(id);
        }
        lab.intents.releaseLease(id, "w");
    }

    // Covers: 7.6 (the cap of 60 counted reads is a backstop: at 59 the step still reads; after the 60th the next due step
    // ends the follow-up with reads_exhausted, ahead of the window's end, and reads nothing)
    @Test
    void sixtyCountedReads_endIt_withReadsExhausted_andFiftyNineDoNot() {
        IntentId id = handedOff();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE));
        seedReads(id, 59);
        pass(id, Duration.ofMinutes(1));
        assertThat(store.reads).hasSize(1);
        assertThat(events(id, "handoff_follow_up_ended")).isEmpty();

        run(id, Duration.ofHours(25));

        assertThat(store.reads).as("the 60th read is the last").hasSize(1);
        assertThat(events(id, "handoff_follow_up_ended")).containsExactly("{\"reads\":60,\"reason\":\"reads_exhausted\"}");
        assertThat(lab.intent(id).handoffWatch().watching()).isFalse();
    }

    // Covers: 7.6, I12, 10 (an entry whose fingerprint changed after the handoff ends the follow-up with merchant_changed,
    // reads nothing, and leaves the state as it is)
    @Test
    void aChangedMerchantEntry_endsIt_withMerchantChanged_andReadsNothing() {
        IntentId id = handedOff();
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE));
        MerchantEntry changed = new MerchantEntry(SHOP, URI.create("https://shop.example"), "Shop", MerchantStatus.ACTIVE,
                Transport.MCP, Completion.HANDOFF, true, Optional.of("other.example"));
        lab.entries = m -> m.equals(SHOP) ? Optional.of(changed) : Optional.empty();

        run(id, Duration.ofMinutes(5));

        assertThat(store.reads).isEmpty();
        assertThat(events(id, "handoff_follow_up_ended")).containsExactly("{\"reads\":0,\"reason\":\"merchant_changed\"}");
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.HANDED_OFF);
    }

    // Covers: 7.6, I8 (a follow-up read's attempt keeps only resolved_by, the status and the order id: never the
    // store's buyer, its fulfillment or the order's permalink or label; no event carries the label or the buyer)
    @Test
    void theAttemptSummary_holdsOnlyResolvedByStatusAndOrderId() {
        IntentId id = handedOff();
        store.afterHandoff(placed());

        run(id, Duration.ofMinutes(2));

        assertThat(followUpReads(id)).singleElement().satisfies(a -> {
            assertThat(a.responseJson()).contains(
                    "{\"order_id\":\"" + ORDER + "\",\"resolved_by\":\"handoff_follow_up\",\"status\":\"completed\"}");
            assertThat(a.idempotencyKey()).isEmpty();
            assertThat(a.requestBody()).isEmpty();
        });
        assertThat(lab.events(id)).noneMatch(e -> e.payloadJson().contains(LABEL)
                || e.payloadJson().contains("canary") || e.payloadJson().contains("Canary"));
    }

    // Covers: 10 V10, 7.6 (an intent handed off with no follow-up, as every intent before V10, is never read)
    @Test
    void anIntentWithNoFollowUp_isNeverRead() {
        IntentId id = handedOff();
        PurchaseIntent intent = lab.intents.claim(id, "w", Duration.ofSeconds(30)).orElseThrow();
        lab.intents.update(intent.withHandoffWatch(HandoffWatch.NONE), intent.version(), "w");
        lab.intents.releaseLease(id, "w");
        store.afterHandoff(placed());

        run(id, Duration.ofHours(1));

        assertThat(store.reads).isEmpty();
        assertThat(lab.events(id)).noneMatch(e -> e.type().startsWith("handoff_"));
    }

    // Covers: 7.6, NFR4 (a crash after the read and before its fenced write: nothing was recorded, so after a restart the
    // read is repeated, which is harmless, and the order is recorded once, counted as the first read)
    @Test
    void aCrashBetweenTheReadAndItsWrite_repeatsTheRead_andRecordsTheOrderOnce() {
        CrashPlan<CrashingIntentRepository.Op> plan = new CrashPlan<>(CrashingIntentRepository.Op.class);
        List<dev.ucplab.core.intent.InMemoryIntentRepository> memory = new ArrayList<>();
        Lab crashing = new Lab(OrchestratorConfig.defaults(), dev.ucplab.core.merchant.fake.FaultyMerchantModel.Mode.LAB,
                clock -> {
                    CrashHarness.Stores s = Lab.inMemoryStores(clock);
                    memory.add((dev.ucplab.core.intent.InMemoryIntentRepository) s.intents());
                    return new CrashHarness.Stores(new CrashingIntentRepository(s.intents(), plan), s.approvals(),
                            s.scan());
                });
        crashing.eventLog = i -> memory.get(0).events(i);
        crashing.entries = lab.entries;
        FollowUpGateway shop = new FollowUpGateway(
                new RewritingGateway(crashing.merchant).rewrite(MerchantCall.GET, HandoffTest.withUrl(URL)), crashing.clock);
        Lab.Worker first = crashing.worker("w1", shop);
        IntentId id = first.orchestrator().propose(crashing.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        crashing.drive(first, id);
        crashing.decide(first, id, true);
        assertThat(first.orchestrator().advance(id).after()).isEqualTo(IntentState.HANDED_OFF);
        shop.afterHandoff(placed());
        crashing.clock.advance(Duration.ofMinutes(1));
        plan.armNext(CrashingIntentRepository.Op.RECORD_ATTEMPT, CrashPlan.Phase.BEFORE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> first.orchestrator().advance(id))
                .isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(shop.reads).hasSize(1);
        assertThat(crashing.intent(id).handoffWatch().storeOrder()).isEmpty();

        crashing.clock.advance(Duration.ofSeconds(31));
        Lab.Worker second = crashing.worker("w2", shop);
        for (int i = 0; i < 3; i++) {
            second.orchestrator().advance(id);
        }

        assertThat(shop.reads).hasSize(2);
        assertThat(crashing.intent(id).handoffWatch().storeOrder()).contains(new StoreOrder(ORDER, Optional.of(LABEL)));
        assertThat(crashing.events(id).stream().filter(e -> e.type().equals("handoff_order_seen"))
                .map(IntentEvent::payloadJson)).containsExactly("{\"order_id\":\"" + ORDER + "\",\"reads\":1}");
    }

    // Covers: 7.6, 8.3 (one worker pass makes at most 2 follow-up reads per merchant: of five due intents at one store,
    // two are read in the first pass, two in the next, one in the third)
    @Test
    void onePass_readsAtMostTwoFollowUpsPerMerchant() {
        List<IntentId> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            List<dev.ucplab.core.intent.ProposedLine> lines = List.of(new dev.ucplab.core.intent.ProposedLine(
                    "item_123", i + 1));
            IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, lines, "buy").intentId();
            lab.drive(worker, id);
            lab.decide(worker, id, true);
            assertThat(worker.orchestrator().advance(id).after()).isEqualTo(IntentState.HANDED_OFF);
            ids.add(id);
        }
        store.afterHandoff(answer(CheckoutStatus.INCOMPLETE));
        lab.clock.advance(Duration.ofMinutes(1));
        RecoveryWorker pass = new RecoveryWorker(lab.intents, lab.scan, worker.orchestrator(), lab.clock,
                lab.clock::advance, Runnable::run, RecoveryConfig.defaults(), "w");

        pass.runOnce();
        assertThat(store.reads).hasSize(2);
        pass.runOnce();
        assertThat(store.reads).hasSize(4);
        pass.runOnce();
        assertThat(store.reads).hasSize(5);
        pass.runOnce();
        assertThat(store.reads).as("none is due again yet").hasSize(5);
    }
}
