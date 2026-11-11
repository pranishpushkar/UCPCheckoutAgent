package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.REJECTED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The persisted cancel_pending flag and the cancel retries of 7.6. */
class CancelPendingTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    private List<MerchantAttempt> cancels(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.CANCEL).toList();
    }

    /** A proposal the user rejected: REJECTED with a session to cancel. */
    private IntentId rejected() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, false)).isInstanceOf(ApprovalDecision.Rejected.class);
        return id;
    }

    // Covers: 7.6, 7.2 (the move to REJECTED sets cancel_pending in the same write, before any cancel is attempted)
    @Test
    void decide_setsCancelPending_inTheWriteThatRejects() {
        IntentId id = rejected();

        assertThat(lab.intent(id).state()).isEqualTo(REJECTED);
        assertThat(lab.intent(id).cancelPending()).isTrue();
        assertThat(cancels(id)).isEmpty();
    }

    // Covers: 7.6 (a definitive answer clears the flag; the state never changes)
    @Test
    void advance_clearsCancelPendingAndKeepsState_whenCancelIsAnswered() {
        ProposalResult r = lab.propose(w, TWO_SHIRTS);
        lab.decide(w, r.intentId(), false);

        assertThat(lab.drive(w, r.intentId())).isEqualTo(REJECTED);

        assertThat(lab.intent(r.intentId()).cancelPending()).isFalse();
        assertThat(cancels(r.intentId())).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED));
        assertThat(lab.merchant.session(r.quote().get().id()).orElseThrow().status()).isEqualTo(CheckoutStatus.CANCELED);
    }

    // Covers: 7.6, 7.4 (an unanswered cancel is retried under the same key cancel/0 until it is answered)
    @Test
    void advance_retriesCancelUnderSameKey_untilAnswered() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 1, new Fault.CommitThenDrop()));
        IntentId id = rejected();

        assertThat(lab.drive(w, id)).isEqualTo(REJECTED);

        assertThat(cancels(id)).hasSize(2);
        assertThat(cancels(id).get(0).status()).isEqualTo(AttemptStatus.UNCERTAIN);
        assertThat(cancels(id).get(1).status()).isEqualTo(AttemptStatus.RESPONDED);
        assertThat(cancels(id)).extracting(a -> a.idempotencyKey().orElseThrow())
                .containsOnly(IdempotencyKey.derive(id, MutatingOp.CANCEL, 0));
        assertThat(lab.intent(id).cancelPending()).isFalse();
    }

    // Covers: 7.6 (gives up after 3 attempts; the flag is cleared and the state is never changed)
    @Test
    void advance_givesUpAfterThreeCancelAttempts_withoutChangingState() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CANCEL, new Fault.NotSent()));
        IntentId id = rejected();

        assertThat(lab.drive(w, id)).isEqualTo(REJECTED);

        assertThat(cancels(id)).hasSize(3);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intent(id).state()).isEqualTo(REJECTED);
        assertThat(lab.drive(w, id)).isEqualTo(REJECTED);
        assertThat(cancels(id)).hasSize(3);
    }

    // Covers: 7.6, NFR4 (a crash after the cancel attempt is written leaves it SENT; the restarted worker tries again)
    @Test
    void advance_retriesCancel_afterProcessDiedWithAttemptSent() {
        IntentId id = rejected();
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashBeforeNext(MerchantCall.CANCEL);
        Lab.Worker dying = lab.worker("worker-b", gateway);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(cancels(id)).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.SENT));
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker restarted = lab.worker("worker-c");

        assertThat(lab.drive(restarted, id)).isEqualTo(REJECTED);
        assertThat(cancels(id)).hasSize(2);
        assertThat(lab.intent(id).cancelPending()).isFalse();
    }

    // Covers: 7.2 (a row with no session to cancel does not set the flag)
    @Test
    void propose_leavesCancelPendingClear_whenPolicyDeniesBeforeAnySession() {
        ProposalResult r = lab.propose(w, List.of(new ProposedLine("item_123", 99)));

        assertThat(r.state()).isEqualTo(REJECTED);
        assertThat(lab.intent(r.intentId()).cancelPending()).isFalse();
        assertThat(lab.merchant.sessionsCreated()).isZero();
    }

    // Covers: 7.2, 7.6 (a failed quote that has a session owes a cancel: FAILED_NOT_CHARGED sets the flag too)
    @Test
    void propose_setsCancelPending_whenQuoteIsDeniedByPolicyAfterSessionExists() {
        ProposalResult r = lab.propose(w, List.of(new ProposedLine("item_789", 2)));

        assertThat(r.state()).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(r.intentId()).cancelPending()).isTrue();
    }
}
