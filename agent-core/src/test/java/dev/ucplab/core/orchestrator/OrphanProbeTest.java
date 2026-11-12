package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.QUOTING;
import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.T0;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
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
 * The orphan-session probe (7.6 "M3 note"): a create whose outcome is unknown may have left a session at the merchant,
 * so the worker re-sends the create under the same key and the same frozen body only to learn the session id, then
 * cancels it. It never completes.
 */
class OrphanProbeTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    private List<MerchantAttempt> attempts(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private long calls(MerchantCall call) {
        return lab.merchant.log().stream().filter(c -> c.call() == call && !c.delivered()).count();
    }

    private void assertNothingWasEverCompleted() {
        assertThat(calls(MerchantCall.COMPLETE)).isZero();
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.6 (M3 note), I1, I4 (create left SENT by a crash after the merchant made the session: the restarted
    // worker probes with the same key and body, stores the session id, cancels it, and never completes)
    @Test
    void advance_probesThenCancels_whenCreateAttemptWasLeftSent() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashAfterNext(MerchantCall.CREATE);
        Lab.Worker dying = lab.worker("worker-a", gateway);
        assertThatThrownBy(() -> lab.propose(dying, TWO_SHIRTS)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        IntentId id = lab.intents.findActiveByDedupeKey(DefaultOrchestrator.dedupeKey(ALICE, SHOP, TWO_SHIRTS))
                .orElseThrow().id();
        assertThat(attempts(id, AttemptOp.CREATE)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.SENT));
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker restarted = lab.worker("worker-b");

        assertThat(lab.drive(restarted, id)).isEqualTo(FAILED_NOT_CHARGED);

        List<MerchantAttempt> creates = attempts(id, AttemptOp.CREATE);
        assertThat(creates).hasSize(2);
        assertThat(creates).extracting(a -> a.idempotencyKey().orElseThrow())
                .containsOnly(IdempotencyKey.derive(id, MutatingOp.CREATE, 0));
        assertThat(creates).extracting(a -> a.requestBody().orElseThrow()).containsOnly(creates.get(0).requestBody().orElseThrow());
        assertThat(creates).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.RESPONDED);
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.sessionId()).isPresent();
        assertThat(lab.merchant.session(intent.sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(attempts(id, AttemptOp.CANCEL)).hasSize(1);
        assertThat(intent.cancelPending()).isFalse();
        assertNothingWasEverCompleted();
    }

    // Covers: 7.6 (M3 note), I1 (create UNCERTAIN after an answer that was not NotSent: the same probe and cancel)
    @Test
    void advance_probesThenCancels_whenCreateAnswerWasUncertain() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(id).state()).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).sessionId()).isEmpty();
        assertThat(attempts(id, AttemptOp.CREATE)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN));

        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(attempts(id, AttemptOp.CREATE)).hasSize(2);
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertNothingWasEverCompleted();
    }

    // Covers: 7.6 (M3 note: NotSent proves no session exists, so nothing is probed)
    @Test
    void advance_sendsNoProbe_whenCreateWasNotSent() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.NotSent()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(attempts(id, AttemptOp.CREATE)).hasSize(1);
        assertThat(calls(MerchantCall.CREATE)).isEqualTo(1);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.merchant.sessionsCreated()).isZero();
    }

    // Covers: 7.6, I7 (a create body that is not byte-identical to the frozen one is never sent)
    @Test
    void advance_sendsNothing_whenFrozenCreateBodyDiffersFromTheRebuiltBody() {
        PurchaseIntent base = PurchaseIntent.proposed(new IntentId("intent-x"), ALICE, SHOP, "dedupe-x", TWO_SHIRTS,
                "alice@example.com", T0);
        lab.intents.insert(base.withState(FAILED_NOT_CHARGED, T0).withCancelPending(true)
                .withLease(Optional.of("setup"), Optional.of(T0)));
        IdempotencyKey key = IdempotencyKey.derive(base.id(), MutatingOp.CREATE, 0);
        lab.intents.recordAttempt(
                MerchantAttempt.sent(new AttemptId("a-1"), base.id(), AttemptOp.CREATE, 0, Optional.of(key),
                        Optional.of(new FrozenBody("{\"line_items\":[]}")), T0),
                lab.intent(base.id()).withState(FAILED_NOT_CHARGED, T0), 0, "setup");
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(w, base.id())).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(lab.merchant.log()).isEmpty();
        assertThat(attempts(base.id(), AttemptOp.CREATE)).hasSize(1);
        assertThat(lab.intent(base.id()).cancelPending()).isFalse();
        assertThat(lab.events(base.id())).anyMatch(e -> e.type().equals("orphan_probe_body_mismatch"));
    }

    // Covers: 7.6 (at most 3 probe attempts, then stop with an operator event; no complete, orders stay 0)
    @Test
    void advance_stopsAfterThreeProbes_whenEveryAnswerIsLost() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CREATE, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(attempts(id, AttemptOp.CREATE)).hasSize(4);
        assertThat(calls(MerchantCall.CREATE)).isEqualTo(4);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intent(id).sessionId()).isEmpty();
        assertThat(lab.events(id)).anyMatch(e -> e.type().equals("orphan_probe_gave_up"));
        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(calls(MerchantCall.CREATE)).isEqualTo(4);
        assertThat(attempts(id, AttemptOp.CANCEL)).isEmpty();
        assertNothingWasEverCompleted();
    }

    // Covers: 7.6 (a worker that dies mid-probe leaves a SENT probe; the next worker resolves it and counts it)
    @Test
    void advance_countsProbeLeftSent_towardTheCap() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashBeforeNext(MerchantCall.CREATE);
        Lab.Worker dying = lab.worker("worker-b", gateway);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(attempts(id, AttemptOp.CREATE)).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.SENT);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(lab.worker("worker-c"), id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(attempts(id, AttemptOp.CREATE)).hasSize(3);
        assertThat(attempts(id, AttemptOp.CREATE)).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.UNCERTAIN, AttemptStatus.RESPONDED);
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertNothingWasEverCompleted();
    }

    // Covers: 7.6 (an intent still QUOTING is not probed; the probe belongs to the terminal intent)
    @Test
    void advance_doesNotProbe_whileIntentIsStillQuoting() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashBeforeNext(MerchantCall.CREATE);
        Lab.Worker dying = lab.worker("worker-a", gateway);
        assertThatThrownBy(() -> lab.propose(dying, TWO_SHIRTS)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);

        assertThat(lab.intents.findActiveByDedupeKey(DefaultOrchestrator.dedupeKey(ALICE, SHOP, TWO_SHIRTS)).orElseThrow()
                .state()).isEqualTo(QUOTING);
        assertThat(calls(MerchantCall.CREATE)).isZero();
    }

    /** Answers every create with Rejected (the merchant holds no session for the key), everything else as the lab does. */
    private final class RejectingCreate implements MerchantGateway {
        @Override
        public MerchantProfile discover(MerchantId merchant) {
            return lab.merchant.discover(merchant);
        }

        @Override
        public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
            return new CallOutcome.Rejected<>(200, List.of());
        }

        @Override
        public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
            return lab.merchant.update(id, request, key);
        }

        @Override
        public CallOutcome<CheckoutView> complete(SessionId id, dev.ucplab.core.merchant.FrozenBody body, IdempotencyKey key) {
            return lab.merchant.complete(id, body, key);
        }

        @Override
        public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
            return lab.merchant.cancel(id, key);
        }

        @Override
        public CallOutcome<CheckoutView> get(SessionId id) {
            return lab.merchant.get(id);
        }
    }

    // Covers: 7.6, 8.1 (a Rejected probe answer means the merchant holds no session for the key: the probe ends at once)
    @Test
    void advance_endsProbe_whenProbeAnswerIsRejected() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(lab.worker("worker-b", new RejectingCreate()), id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(attempts(id, AttemptOp.CREATE)).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.RESPONDED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intent(id).sessionId()).isEmpty();
        assertThat(lab.events(id)).anyMatch(e -> e.type().equals("orphan_probe_no_session"))
                .noneMatch(e -> e.type().equals("orphan_probe_gave_up"));
        assertThat(attempts(id, AttemptOp.CANCEL)).isEmpty();
    }
}
