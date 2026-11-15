package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The owed cancel and the orphan-session probe (7.6, 7.4, 8.2, I1, I4, I7), found unchecked by the H3 P7 mutation pass,
 * group 4: how many tries each gets, what is stored about each, what an operator reads in the events, and what is never
 * sent. These are the paths where a worker sends a merchant call for an intent that is already terminal, so a wrong count or
 * a wrong guard sends too many or none.
 */
class OrchestratorOwedCancelMutationTest {

    private Lab lab = new Lab();
    private Lab.Worker w = lab.worker("worker-a");

    private IntentId rejected() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, false);
        assertThat(lab.intent(id).cancelPending()).isTrue();
        return id;
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> attempts(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private long clientCalls(MerchantCall call) {
        return lab.merchant.log().stream().filter(c -> c.call() == call && !c.delivered()).count();
    }

    // ---------------------------------------------------------------- the owed cancel

    // Covers: 7.6, 7.4, I1. Kills DefaultOrchestrator.cancelIfOwed:1177 ConditionalsBoundary, Math and RemoveConditional (the
    // third unanswered cancel is the last), :1190 and :1193 events and :1190 Math (the attempt numbers): a cancel that is never
    // answered is sent exactly three times under one key, each recorded with its number, and the third says it gave up
    @Test
    void anUnansweredCancel_isSentThreeTimes_numbered_andTheThirdGivesUp() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CANCEL, new Fault.NotSent()));
        IntentId id = rejected();

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.REJECTED);

        assertThat(clientCalls(MerchantCall.CANCEL)).isEqualTo(3);
        assertThat(events(id, "cancel_sent")).hasSize(3);
        assertThat(events(id, "cancel_sent").get(0)).contains("\"attempt\":1").contains("\"outcome\":\"not_sent\"");
        assertThat(events(id, "cancel_sent").get(1)).contains("\"attempt\":2");
        assertThat(events(id, "cancel_sent").get(2)).contains("\"attempt\":3");
        assertThat(events(id, "cancel_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"attempts\":3"));
        assertThat(attempts(id, AttemptOp.CANCEL)).extracting(MerchantAttempt::status).containsOnly(AttemptStatus.UNCERTAIN);
        assertThat(lab.intent(id).cancelPending()).isFalse();
    }

    // Covers: 7.6, 7.4, NFR4. Kills DefaultOrchestrator.cancelIfOwed:1159 ConditionalsBoundary and RemoveConditional_ORDER_ELSE,
    // :1161 update, :1162 event, :1163 return (NO_COVERAGE): when a third attempt was left SENT by a crash the restarted worker
    // counts it, sends no fourth, clears the flag and records that it gave up
    @Test
    void aThirdCancelLeftSentByACrash_isCounted_andNoFourthIsSent() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CANCEL, new Fault.NotSent()));
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        Lab.Worker dying = lab.worker("worker-d", gateway);
        IntentId id = lab.propose(dying, TWO_SHIRTS).intentId();
        lab.decide(dying, id, false);
        dying.orchestrator().advance(id);
        dying.orchestrator().advance(id);
        gateway.crashBeforeNext(MerchantCall.CANCEL);
        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(attempts(id, AttemptOp.CANCEL)).hasSize(3);
        assertThat(clientCalls(MerchantCall.CANCEL)).isEqualTo(2);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker restarted = lab.worker("worker-e");

        restarted.orchestrator().advance(id);

        assertThat(clientCalls(MerchantCall.CANCEL)).as("no fourth cancel").isEqualTo(2);
        assertThat(attempts(id, AttemptOp.CANCEL)).hasSize(3);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(events(id, "cancel_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"attempts\":3"));
    }

    // Covers: 7.6, 8.1, 8.2. Kills DefaultOrchestrator.cancelIfOwed:1173 RemoveConditional_EQUAL_ELSE / EQUAL_IF (NO_COVERAGE) and
    // isRetryable:1358 (all seven, NO_COVERAGE): a protocol error answers the cancel for good unless it asks to be retried
    // (a Retry-After, a 429 or a 503); a definitive one is stored RESPONDED with its status and clears the flag, a retryable
    // one is stored UNCERTAIN and keeps the flag
    @Test
    void aProtocolErrorOnCancel_isDefinitiveUnlessItAsksToBeRetried() {
        for (Fault.Reject refusal : List.of(
                new Fault.Reject(404, "not_found", Optional.empty()), new Fault.Reject(400, "bad", Optional.empty()))) {
            lab = new Lab();
            w = lab.worker("worker-a");
            lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 1, refusal));
            IntentId id = rejected();

            w.orchestrator().advance(id);

            assertThat(attempts(id, AttemptOp.CANCEL)).as(refusal.toString()).singleElement().satisfies(a -> {
                assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED);
                assertThat(a.httpStatus()).contains(refusal.httpStatus());
            });
            assertThat(lab.intent(id).cancelPending()).as(refusal.toString()).isFalse();
            assertThat(events(id, "cancel_sent")).singleElement().satisfies(e -> assertThat(e)
                    .contains("\"outcome\":\"protocol_error\"").contains("\"attempt\":1"));
        }
        for (Fault.Reject retry : List.of(
                new Fault.Reject(429, "slow", Optional.empty()), new Fault.Reject(503, "busy", Optional.empty()),
                new Fault.Reject(400, "later", Optional.of(java.time.Duration.ofSeconds(5))))) {
            lab = new Lab();
            w = lab.worker("worker-a");
            lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 1, retry));
            IntentId id = rejected();

            w.orchestrator().advance(id);

            assertThat(attempts(id, AttemptOp.CANCEL)).as(retry.toString()).singleElement()
                    .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN));
            assertThat(lab.intent(id).cancelPending()).as(retry.toString()).isTrue();
        }
    }

    // Covers: 7.6, 8.1. Kills DefaultOrchestrator.cancelIfOwed:1183 and :1186 (the stored answer): an answered cancel stores the
    // merchant's response summary (the session now canceled), a lost one stores what happened; both are visible on the attempt
    @Test
    void aCancelAttempt_storesWhatCameBack() {
        IntentId id = rejected();
        w.orchestrator().advance(id);
        assertThat(attempts(id, AttemptOp.CANCEL)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED);
            assertThat(a.httpStatus()).contains(200);
            assertThat(a.responseJson().orElseThrow()).contains("\"status\":\"canceled\"");
        });

        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 1, new Fault.NotSent()));
        IntentId lost = rejected();
        w.orchestrator().advance(lost);
        assertThat(attempts(lost, AttemptOp.CANCEL)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN);
            assertThat(a.responseJson().orElseThrow()).contains("\"outcome\":\"not_sent\"");
        });
    }

    // ---------------------------------------------------------------- the orphan probe

    private IntentId withCreatesLost() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        return lab.propose(w, TWO_SHIRTS).intentId();
    }

    /** A terminal intent that owes a cancel and has no session, with the given create attempts already stored. */
    private IntentId orphan(String id, List<MerchantAttempt> creates, List<MerchantAttempt> others) {
        IntentId intentId = new IntentId(id);
        PurchaseIntent base = PurchaseIntent.proposed(intentId, ALICE, SHOP, "dedupe-" + id, TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.FAILED_NOT_CHARGED, lab.clock.instant()).withCancelPending(true)
                .withLease(Optional.of("setup"), Optional.of(lab.clock.instant()));
        lab.intents.insert(base);
        long version = 0;
        for (MerchantAttempt a : java.util.stream.Stream.concat(creates.stream(), others.stream()).toList()) {
            lab.intents.recordAttempt(a, lab.intent(intentId).withState(IntentState.FAILED_NOT_CHARGED, lab.clock.instant()),
                    version++, "setup");
        }
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return intentId;
    }

    private MerchantAttempt create(IntentId id, String attemptId, AttemptStatus status, Optional<FrozenBody> body,
            Optional<IdempotencyKey> key, int revision, AttemptOp op) {
        return new MerchantAttempt(new AttemptId(attemptId), id, op, revision, key, Optional.empty(), body, status,
                Optional.empty(), Optional.empty(), lab.clock.instant(), Optional.empty());
    }

    private FrozenBody rebuiltBody() {
        return new FrozenBody(dev.ucplab.core.merchant.CheckoutWire.createBody(
                new dev.ucplab.core.merchant.CreateCheckout(TWO_SHIRTS, Optional.of("alice@example.com"))));
    }

    // Covers: 7.6 M3 note, I1, I4. Kills DefaultOrchestrator.probeOrphanSession:1209 RemoveConditional_EQUAL_ELSE, :1210
    // NullReturnVals (NO_COVERAGE) and :1208 (BooleanTrue, both EQUAL_IF): an intent that owes a cancel but has no create
    // attempt of revision 0 (an update does not count) sends nothing, clears the flag and says why
    @Test
    void anOrphanWithNoCreateAttempt_isStopped_andNothingIsSent() {
        IntentId id = new IntentId("none");
        IdempotencyKey key = IdempotencyKey.derive(id, MutatingOp.UPDATE, 0);
        IntentId made = orphan("none", List.of(),
                List.of(create(id, "u-1", AttemptStatus.RESPONDED, Optional.empty(), Optional.of(key), 0, AttemptOp.UPDATE)));

        lab.drive(w, made);

        assertThat(events(made, "orphan_probe_no_create")).hasSize(1);
        assertThat(lab.intent(made).cancelPending()).isFalse();
        assertThat(lab.merchant.log()).isEmpty();
    }

    // Covers: 7.6 M3 note, I7. Kills DefaultOrchestrator.probeOrphanSession:1220 RemoveConditional_EQUAL_IF (both): a create
    // attempt stored without its frozen body, or without its key, is never probed: nothing is sent and the event names the
    // attempt
    @Test
    void anOrphanWhoseCreateHasNoBodyOrNoKey_isNeverProbed() {
        IntentId id = new IntentId("nobody");
        IdempotencyKey key = IdempotencyKey.derive(id, MutatingOp.CREATE, 0);
        IntentId noBody = orphan("nobody", List.of(
                create(id, "c-1", AttemptStatus.UNCERTAIN, Optional.empty(), Optional.of(key), 0, AttemptOp.CREATE)), List.of());
        lab.drive(w, noBody);
        assertThat(events(noBody, "orphan_probe_body_mismatch")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"attempt_id\":\"c-1\""));
        assertThat(lab.merchant.log()).isEmpty();

        IntentId id2 = new IntentId("nokey");
        IntentId noKey = orphan("nokey", List.of(
                create(id2, "c-2", AttemptStatus.UNCERTAIN, Optional.of(rebuiltBody()), Optional.empty(), 0, AttemptOp.CREATE)),
                List.of());
        lab.drive(w, noKey);
        assertThat(events(noKey, "orphan_probe_body_mismatch")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"attempt_id\":\"c-2\""));
        assertThat(lab.merchant.log()).isEmpty();
    }

    // Covers: 7.6 M3 note. Kills DefaultOrchestrator.probeOrphanSession:1213 ConditionalsBoundary / RemoveConditional_ORDER_ELSE
    // and :1214 NullReturnVals (NO_COVERAGE): with the original create and three probes already made, a fourth is never sent:
    // the probe gives up, clears the flag and records how many probes were made
    @Test
    void anOrphanThatHadThreeProbes_isNotProbedAFourthTime() {
        IntentId id = new IntentId("capped");
        IdempotencyKey key = IdempotencyKey.derive(id, MutatingOp.CREATE, 0);
        List<MerchantAttempt> four = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(create(id, "c-" + i, AttemptStatus.UNCERTAIN, Optional.of(rebuiltBody()), Optional.of(key), 0,
                    AttemptOp.CREATE));
        }
        IntentId made = orphan("capped", four, List.of());

        lab.drive(w, made);

        assertThat(lab.merchant.log()).isEmpty();
        assertThat(events(made, "orphan_probe_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"probes\":3"));
        assertThat(lab.intent(made).cancelPending()).isFalse();
    }

    // Covers: 7.6 M3 note, I1, I4. Kills DefaultOrchestrator.probeOrphanSession:1226 BooleanTrue / RemoveConditional_EQUAL_IF,
    // :1228 (an attempt that was answered is not reset to uncertain by a later probe), :1240 Math and event (the session found
    // is recorded with its probe number), :1252 (a probe answered with a protocol error is stored RESPONDED, a lost one
    // UNCERTAIN), :1259 Math and event (every probe is recorded with its outcome and number)
    @Test
    void probes_areNumbered_andEachAttemptKeepsItsOwnStatus() {
        IntentId id = withCreatesLost();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 2, new Fault.Reject(409, "conflict", Optional.empty())));

        lab.drive(w, id);

        assertThat(attempts(id, AttemptOp.CREATE)).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.RESPONDED, AttemptStatus.RESPONDED);
        assertThat(events(id, "orphan_probe_sent")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"outcome\":\"protocol_error\"").contains("\"probe\":1"));
        assertThat(events(id, "orphan_session_found")).singleElement().satisfies(e -> assertThat(e).contains("\"probe\":2"));
        assertThat(lab.intent(id).sessionId()).isPresent();
        assertThat(attempts(id, AttemptOp.CANCEL)).hasSize(1);
    }

    // Covers: 7.6 M3 note. Kills DefaultOrchestrator.probeOrphanSession:1255 ConditionalsBoundary, Math and RemoveConditional,
    // :1256, :1257 (RemoveConditional, VoidMethodCall resolve), :1259, :1260 and :1261 Math (the third probe is the last):
    // three lost probes are sent, numbered one to three, the third says it gave up with the count, and the original plus three
    // probes are all stored UNCERTAIN
    @Test
    void threeLostProbes_areNumbered_andTheThirdGivesUp() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CREATE, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        lab.drive(w, id);

        assertThat(events(id, "orphan_probe_sent")).hasSize(3);
        assertThat(events(id, "orphan_probe_sent").get(0)).contains("\"probe\":1").contains("\"outcome\":\"uncertain\"");
        assertThat(events(id, "orphan_probe_sent").get(1)).contains("\"probe\":2");
        assertThat(events(id, "orphan_probe_sent").get(2)).contains("\"probe\":3");
        assertThat(events(id, "orphan_probe_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"probes\":3"));
        assertThat(attempts(id, AttemptOp.CREATE)).hasSize(4)
                .extracting(MerchantAttempt::status).containsOnly(AttemptStatus.UNCERTAIN);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(clientCalls(MerchantCall.COMPLETE)).isZero();
    }

    // Covers: 7.6 M3 note, 8.1. Kills DefaultOrchestrator.probeOrphanSession:1248 Math and event (a Rejected probe says no
    // session exists, with its number)
    @Test
    void aRejectedProbe_isRecordedWithItsNumber() {
        IntentId id = withCreatesLost();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        RewritingGateway rejecting = new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                RewritingGateway.always(new CallOutcome.Rejected<CheckoutView>(200,
                        List.of(RewritingGateway.error("out_of_stock")))));

        lab.drive(lab.worker("worker-b", rejecting), id);

        assertThat(events(id, "orphan_probe_no_session")).singleElement().satisfies(e -> assertThat(e).contains("\"probe\":1"));
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(attempts(id, AttemptOp.CANCEL)).isEmpty();
    }

    // Covers: I9, 7.7. Kills DefaultOrchestrator.lambda$existingProposal$41:1278 BooleanTrueReturnVals: a proposal never returns
    // another user's intent, not even one that holds the same dedupe key (which the key, built from the user, makes
    // impossible in practice, so it is forced here): the insert is refused instead
    @Test
    void aProposal_neverReturnsAnotherUsersIntent() {
        String key = DefaultOrchestrator.dedupeKey(ALICE, SHOP, TWO_SHIRTS);
        lab.intents.insert(PurchaseIntent.proposed(new IntentId("bobs"), Lab.BOB, SHOP, key, TWO_SHIRTS, "bob@example.com",
                lab.clock.instant()).withState(IntentState.QUOTING, lab.clock.instant()));

        assertThatThrownBy(() -> lab.propose(w, TWO_SHIRTS))
                .isInstanceOf(dev.ucplab.core.intent.DuplicateIntentException.class);
    }

    // Covers: 7.3, I7. Kills DefaultOrchestrator.lambda$newAttempt$43:1290 EmptyObjectReturnVals: an attempt stores the hash of
    // the body it sent, so a replay can be compared with it
    @Test
    void anAttempt_storesTheHashOfItsBody() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        MerchantAttempt create = attempts(id, AttemptOp.CREATE).get(0);

        assertThat(create.requestHash()).contains(Sha256.hex(create.requestBody().orElseThrow().json()));
    }
}
