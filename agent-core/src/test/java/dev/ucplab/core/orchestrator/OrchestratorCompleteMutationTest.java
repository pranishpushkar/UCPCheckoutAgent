package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.RewritingGateway.error;
import static dev.ucplab.core.orchestrator.RewritingGateway.warning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code DefaultOrchestrator} takes about {@code complete} (7.2, 7.4, 8.1, I1, I3, I4, I7) that the H3 P7
 * mutation pass found unchecked, group 2: what each kind of answer to a complete leads to, what is written before it is
 * sent, and what a worker that did not send it may do. Each test asserts what an operator or a merchant could see: the
 * state, the failure reason, the transition event, the stored attempts, the calls that reached the merchant.
 */
class OrchestratorCompleteMutationTest {

    private Lab lab = new Lab();
    private final Lab.Worker plain = lab.worker("worker-a");

    private IntentId approved(Lab.Worker w) {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        return id;
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> completes(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    /** One step of a worker whose merchant answers {@code complete} as {@code how} says. */
    private PurchaseIntent oneStep(java.util.function.Consumer<RewritingGateway> configure) {
        lab = new Lab();    // a fresh lab: the same basket would be deduplicated against the earlier intent
        RewritingGateway gateway = new RewritingGateway(lab.merchant);
        configure.accept(gateway);
        Lab.Worker w = lab.worker("worker-r", gateway);
        IntentId id = approved(w);
        w.orchestrator().advance(id);
        return lab.intent(id);
    }

    private PurchaseIntent answering(CheckoutStatus status, boolean keepOrder, dev.ucplab.core.merchant.MerchantMessage... messages) {
        return oneStep(g -> g.rewrite(MerchantCall.COMPLETE, as(status, keepOrder, messages)));
    }

    // ---------------------------------------------------------------- the kinds of answer to a complete

    // Covers: 8.1, 7.2, I1. Kills DefaultOrchestrator.onCompleteOutcome:834 RemoveConditional_EQUAL_ELSE: a complete answered
    // requires_escalation is not proof of no charge, even when the answer carries an error message: it reconciles, and is
    // never a FAILED_NOT_CHARGED
    @Test
    void completeAnsweredRequiresEscalation_withAnError_reconciles() {
        PurchaseIntent after = answering(CheckoutStatus.REQUIRES_ESCALATION, false, error("needs_buyer"));

        assertThat(after.state()).isEqualTo(IntentState.RECONCILING);
        assertThat(after.failureReason()).isEmpty();
    }

    // Covers: 7.2, I1, I2. Kills DefaultOrchestrator.onCompleteOutcome:838 RemoveConditional_EQUAL_IF (both): an answer that says
    // completed but names no order is not COMPLETED: there is no order id to store, so the intent reconciles
    @Test
    void completeAnsweredCompletedWithNoOrder_isNotCompleted_itReconciles() {
        PurchaseIntent after = answering(CheckoutStatus.COMPLETED, false);

        assertThat(after.state()).isEqualTo(IntentState.RECONCILING);
        assertThat(after.orderId()).isEmpty();
    }

    // Covers: 7.2, 8.1, I3. Kills DefaultOrchestrator.onCompleteOutcome:849 RemoveConditional_EQUAL_ELSE / EQUAL_IF: an
    // incomplete answer is a price change when it carries price_changed, and "checkout incomplete" otherwise; the transition
    // event says which
    @Test
    void incompleteAnswers_areToldApart_inTheTransitionEvent() {
        PurchaseIntent price = answering(CheckoutStatus.INCOMPLETE, false, warning("price_changed"));
        assertThat(price.state()).isEqualTo(IntentState.QUOTING);
        assertThat(price.requoteCount()).isEqualTo(1);
        assertThat(events(price.id(), "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"PRICE_CHANGED\""));

        PurchaseIntent other = answering(CheckoutStatus.INCOMPLETE, false, error("missing"));
        assertThat(other.state()).isEqualTo(IntentState.QUOTING);
        assertThat(events(other.id(), "transition")).anySatisfy(e -> assertThat(e)
                .contains("\"event\":\"CHECKOUT_INCOMPLETE\"")).noneSatisfy(e -> assertThat(e).contains("PRICE_CHANGED"));
    }

    // Covers: 7.2, 8.1, S7. Kills DefaultOrchestrator.onCompleteOutcome:858 and :859 RemoveConditional_EQUAL_ELSE / EQUAL_IF,
    // :861 failure reason, :864 transitionEvent: a ready_for_complete answer with payment_declined or payment_invalid ends
    // FAILED_NOT_CHARGED with the reason payment_declined and a transition event; the same code on another status is not a
    // decline
    @Test
    void aDeclineOrAnInvalidPayment_endsNotCharged_withReasonPaymentDeclined_andATransitionEvent() {
        for (String code : List.of("payment_declined", "payment_invalid")) {
            PurchaseIntent after = answering(CheckoutStatus.READY_FOR_COMPLETE, false, error(code));

            assertThat(after.state()).as(code).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(after.failureReason()).as(code).contains("payment_declined");
            assertThat(events(after.id(), "transition")).as(code).anySatisfy(e -> assertThat(e)
                    .contains("\"from\":\"COMPLETING\"").contains("\"event\":\"PAYMENT_DECLINED\"")
                    .contains("\"to\":\"FAILED_NOT_CHARGED\""));
        }
        PurchaseIntent canceled = answering(CheckoutStatus.CANCELED, false, error("payment_declined"));
        assertThat(canceled.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(canceled.failureReason()).contains("payment_declined");
    }

    // Covers: 7.2 (definitive failure answer), 8.1. Kills DefaultOrchestrator.onCompleteOutcome:871 RemoveConditional_EQUAL_IF
    // and :864/:879 transitionEvent: an error answer that is not COMPLETED and not COMPLETE_IN_PROGRESS is "not charged" with
    // the merchant's code as the reason (a table 7.2 code as it is, any other prefixed) and a transition event; a
    // complete_in_progress answer, or a completed one, is never a failure: it reconciles
    @Test
    void anErrorAnswer_endsNotCharged_exceptWhenACompleteIsRunningOrDone() {
        PurchaseIntent stock = answering(CheckoutStatus.READY_FOR_COMPLETE, false, error("out_of_stock"));
        assertThat(stock.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(stock.failureReason()).contains("out_of_stock");
        assertThat(events(stock.id(), "transition")).anySatisfy(e -> assertThat(e).contains("\"to\":\"FAILED_NOT_CHARGED\""));

        PurchaseIntent odd = answering(CheckoutStatus.CANCELED, false, error("checkout_canceled"));
        assertThat(odd.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(odd.failureReason()).contains("complete_error:checkout_canceled");

        PurchaseIntent running = answering(CheckoutStatus.COMPLETE_IN_PROGRESS, false, error("complete_in_progress"));
        assertThat(running.state()).isEqualTo(IntentState.RECONCILING);

        PurchaseIntent doneNoOrder = answering(CheckoutStatus.COMPLETED, false, error("odd"));
        assertThat(doneNoOrder.state()).isEqualTo(IntentState.RECONCILING);
    }

    // Covers: 7.2, 8.2. Kills DefaultOrchestrator.onCompleteOutcome:890 RemoveConditional_EQUAL_IF: a protocol error on
    // complete and an uncertain one both reconcile, and the transition event names which it was
    @Test
    void protocolErrorAndUncertain_bothReconcile_withTheirOwnTransitionEvent() {
        lab = new Lab();
        Lab.Worker w = lab.worker("worker-p");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.Reject(400, "bad", Optional.empty())));
        IntentId rejected = approved(w);
        w.orchestrator().advance(rejected);
        assertThat(lab.intent(rejected).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events(rejected, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"PROTOCOL_ERROR\""));

        lab = new Lab();
        Lab.Worker w2 = lab.worker("worker-p");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId dropped = approved(w2);
        w2.orchestrator().advance(dropped);
        assertThat(lab.intent(dropped).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events(dropped, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"UNCERTAIN\""))
                .noneSatisfy(e -> assertThat(e).contains("PROTOCOL_ERROR"));
    }

    // ---------------------------------------------------------------- a resend settles the attempts under its key

    // Covers: 7.4, 7.5, I1, I7. Kills DefaultOrchestrator.onCompleteOutcome:841, :862 and :877 settleSiblings (VoidMethodCall)
    // and DefaultOrchestrator.resend:960 event (complete_resent): a complete that was never delivered is resent under the same
    // key; the answer to the resend settles the earlier attempt under that key, as the merchant's stored result for it, for
    // each way the resend can end
    @Test
    void anAnswerToAResend_settlesTheEarlierAttemptUnderTheSameKey() {
        record Ending(String token, String observed, IntentState state, int order) {}
        for (Ending ending : List.of(new Ending("tok_ok", "completed", IntentState.COMPLETED, 1),
                new Ending("tok_decline", "payment_declined", IntentState.FAILED_NOT_CHARGED, 0))) {
            Lab l = new Lab();
            l.payWith(ending.token());
            Lab.Worker w = l.worker("worker-s");
            l.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
            IntentId id = l.propose(w, TWO_SHIRTS).intentId();
            assertThat(l.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);

            assertThat(l.drive(w, id)).as(ending.token()).isEqualTo(ending.state());

            List<MerchantAttempt> completes = l.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
            assertThat(completes).hasSize(2);
            assertThat(completes).extracting(MerchantAttempt::status).containsOnly(AttemptStatus.RESPONDED);
            assertThat(completes.get(0).responseJson().orElseThrow()).contains("\"resolved_by\":\"same_key_answer\"")
                    .contains("\"observed\":\"" + ending.observed() + "\"");
            assertThat(completes.get(0).idempotencyKey()).isEqualTo(completes.get(1).idempotencyKey());
            assertThat(l.merchant.orders()).hasSize(ending.order());
            assertThat(l.events(id).stream().filter(e -> e.type().equals("complete_resent")).map(IntentEvent::payloadJson))
                    .singleElement().satisfies(e -> assertThat(e).contains("\"resend\":1"));
        }
    }

    // Covers: 7.4, 7.5, I1. Kills DefaultOrchestrator.onCompleteOutcome:877 settleSiblings for a generic error: the same for an
    // answer that is an error other than a decline
    @Test
    void anErrorAnswerToAResend_settlesTheEarlierAttempt_asNotCharged() {
        RewritingGateway merchant = new RewritingGateway(lab.merchant);
        Lab.Worker w = lab.worker("worker-s", merchant);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(w);
        merchant.rewrite(MerchantCall.COMPLETE, as(CheckoutStatus.CANCELED, false, error("checkout_canceled")));

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        assertThat(completes(id)).extracting(MerchantAttempt::status).containsOnly(AttemptStatus.RESPONDED);
        assertThat(completes(id).get(0).responseJson().orElseThrow()).contains("\"observed\":\"not_charged\"");
    }

    // Covers: 7.4, 7.5, I1. Kills DefaultOrchestrator.onCompleteOutcome:853 settleSiblings (the incomplete answer)
    @Test
    void anIncompleteAnswerToAResend_settlesTheEarlierAttempt() {
        RewritingGateway merchant = new RewritingGateway(lab.merchant);
        Lab.Worker w = lab.worker("worker-s", merchant);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(w);
        merchant.rewrite(MerchantCall.COMPLETE, as(CheckoutStatus.INCOMPLETE, false, warning("price_changed")));

        for (int i = 0; i < 40 && completes(id).size() < 2; i++) {
            w.orchestrator().advance(id);
            lab.clock.advance(Duration.ofSeconds(5));
        }

        assertThat(completes(id)).hasSize(2);
        assertThat(completes(id).get(0).status()).isEqualTo(AttemptStatus.RESPONDED);
        assertThat(completes(id).get(0).responseJson().orElseThrow()).contains("\"observed\":\"incomplete\"");
    }

    // ---------------------------------------------------------------- COMPLETING without a live call

    // Covers: 7.3, I1. Kills DefaultOrchestrator.completing:915 RemoveConditional_EQUAL_ELSE: an intent in COMPLETING with no
    // complete attempt at all is a corrupt state, refused loudly, never advanced
    @Test
    void completingWithNoCompleteAttempt_isRefused() {
        IntentId id = approved(plain);
        PurchaseIntent claimed = lab.intents.claim(id, "worker-a", lab.config.lease()).orElseThrow();
        lab.intents.update(claimed.withState(IntentState.COMPLETING, lab.clock.instant()), claimed.version(), "worker-a");

        assertThatThrownBy(() -> plain.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETING without a complete attempt");
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
    }

    // Covers: 7.3 crash point, 7.5, I1. Kills DefaultOrchestrator.completing:926 transitionEvent: after a crash between the
    // write-ahead and the request, the next worker finds the attempt SENT, sends nothing, marks it UNCERTAIN as resolved by
    // recovery, and records the transition to RECONCILING
    @Test
    void aSentCompleteAfterACrash_isReconciled_notResent_andTheTransitionIsRecorded() {
        InterceptingGateway dying = new InterceptingGateway(lab.merchant);
        Lab.Worker first = lab.worker("worker-d", dying);
        IntentId id = approved(first);
        dying.crashBeforeNext(MerchantCall.COMPLETE);
        assertThatThrownBy(() -> first.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker restarted = lab.worker("worker-e");

        restarted.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(completes(id)).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN);
            assertThat(a.responseJson().orElseThrow()).contains("\"resolved_by\":\"recovery\"");
        });
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        assertThat(events(id, "transition")).anySatisfy(e -> assertThat(e).contains("\"from\":\"COMPLETING\"")
                .contains("\"event\":\"UNCERTAIN\"").contains("\"to\":\"RECONCILING\""));
    }

    // Covers: 7.5, 7.6, I1, I7. Kills DefaultOrchestrator.completing:934 RemoveConditional_EQUAL_IF, :941 event and :942
    // transitionEvent: a resend that reconciliation approved is continued only by the process that approved it; another
    // worker that finds the intent in COMPLETING sends nothing, says so in an event, and sends it back to RECONCILING
    @Test
    void aResendApprovedByAnotherProcess_isNotSentHere() {
        Lab.Worker a = lab.worker("worker-a2");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(a);
        for (int i = 0; i < 40 && lab.intent(id).state() != IntentState.COMPLETING; i++) {
            lab.waitUntilNextAttempt(a.orchestrator().advance(id));
        }
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETING);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker b = lab.worker("worker-b2");

        b.orchestrator().advance(id);

        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).as("nothing was sent by the other worker").isZero();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events(id, "resend_not_approved_here")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"attempt_id\":\"" + completes(id).get(0).id().value() + "\""));
        assertThat(events(id, "transition")).anySatisfy(e -> assertThat(e).contains("\"from\":\"COMPLETING\"")
                .contains("\"to\":\"RECONCILING\""));
    }

    // ---------------------------------------------------------------- before the complete is sent

    private PurchaseIntent leased(IntentId id) {
        return lab.intents.claim(id, "worker-a", lab.config.lease()).orElseThrow();
    }

    // Covers: 9.2, I3. Kills DefaultOrchestrator.lambda$sendComplete$22:785 RemoveConditional_EQUAL_IF (both operands): an
    // approved intent whose approved snapshot hash is missing, or differs from the newest approval's, is never sent: it is
    // ESCALATED as approval_invalid, with no complete attempt and no call to the merchant
    @Test
    void anApprovedIntentWhoseHashIsMissingOrDiffers_isEscalated_andNothingIsSent() {
        for (Optional<String> hash : List.of(Optional.<String>empty(), Optional.of("not-the-approved-hash"))) {
            Lab l = new Lab();
            Lab.Worker w = l.worker("worker-h");
            IntentId id = l.propose(w, TWO_SHIRTS).intentId();
            assertThat(l.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
            PurchaseIntent claimed = l.intents.claim(id, "worker-h", l.config.lease()).orElseThrow();
            l.intents.update(claimed.withApprovedSnapshotHash(hash), claimed.version(), "worker-h");

            w.orchestrator().advance(id);

            assertThat(l.intent(id).state()).as(hash.toString()).isEqualTo(IntentState.ESCALATED);
            assertThat(l.intent(id).failureReason()).contains("approval_invalid");
            assertThat(l.intents.attempts(id).stream().filter(x -> x.op() == AttemptOp.COMPLETE)).isEmpty();
            assertThat(l.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        }
    }

    // Covers: 7.4, I1, I7. Kills DefaultOrchestrator.sendComplete:797 ConditionalsBoundary, RemoveConditional_ORDER_ELSE and
    // RemoveConditional_EQUAL_ELSE: a complete that already exists under this revision is never sent again as a new one: the
    // step refuses and nothing leaves
    @Test
    void aSecondCompleteUnderTheSameRevision_isRefused() {
        IntentId id = approved(plain);
        PurchaseIntent claimed = leased(id);
        MerchantAttempt earlier = completeAttempt(id, 0, AttemptStatus.RESPONDED, "earlier");
        lab.intents.recordAttempt(earlier, claimed, claimed.version(), "worker-a");

        assertThatThrownBy(() -> plain.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a complete already exists under revision 0");
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
    }

    // Covers: 7.4, I1, I7. Kills DefaultOrchestrator.sendComplete:800 RemoveConditional_EQUAL_ELSE and
    // lambda$sendComplete$26:800: a new key (a re-quote) is issued only when every earlier complete attempt is resolved: an
    // unresolved one blocks it, a resolved one does not
    @Test
    void aNewKey_needsEveryEarlierCompleteToBeResolved() {
        IntentId id = approved(plain);
        PurchaseIntent claimed = leased(id);
        PurchaseIntent requoted = claimed.withRequoteCount(1);
        MerchantAttempt unresolved = completeAttempt(id, 0, AttemptStatus.UNCERTAIN, "earlier");
        lab.intents.recordAttempt(unresolved, requoted, claimed.version(), "worker-a");

        assertThatThrownBy(() -> plain.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("an earlier complete attempt is unresolved");
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();

        lab.intents.resolveAttempt(unresolved.id(), AttemptStatus.RESPONDED, Optional.of(200), Optional.of("{}"));
        plain.orchestrator().advance(id);

        assertThat(completes(id)).hasSize(2);
        assertThat(completes(id).get(1).revision()).isEqualTo(1);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
    }

    private MerchantAttempt completeAttempt(IntentId id, int revision, AttemptStatus status, String attemptId) {
        return new MerchantAttempt(new dev.ucplab.core.intent.AttemptId(attemptId), id, AttemptOp.COMPLETE, revision,
                Optional.of(IdempotencyKey.derive(id, MutatingOp.COMPLETE, revision)), Optional.empty(),
                Optional.of(new FrozenBody("{\"payment\":{}}")), status, Optional.empty(), Optional.empty(),
                lab.clock.instant(), Optional.empty());
    }

    // Covers: 7.6, 7.3, I10. Kills DefaultOrchestrator.sendComplete:815 RemoveConditional_EQUAL_IF and :818 event: an approval
    // still PENDING under an APPROVED intent (a crash inside decide) is repaired before complete is sent, by the owner's
    // decision, and the repair is recorded; a normal approval is not "recovered"
    @Test
    void aPendingApprovalUnderAnApprovedIntent_isRepairedBeforeTheComplete_andARegularOneIsNot() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        Lab.Worker dying = lab.worker("worker-x", lab.merchant, crashing);
        IntentId id = lab.propose(dying, TWO_SHIRTS).intentId();
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(lab.worker("worker-y"), id)).isEqualTo(IntentState.COMPLETED);

        ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(approval.decidedBy()).contains(Lab.ALICE);
        assertThat(events(id, "approval_decision_recovered")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"approval_id\":\"" + approval.id() + "\""));
        assertThat(lab.merchant.orders()).hasSize(1);

        IntentId regular = approved(plain);
        lab.drive(plain, regular);
        assertThat(events(regular, "approval_decision_recovered")).isEmpty();
    }

    // ---------------------------------------------------------------- found in the second round of the pass

    // Covers: 7.2, I1, I2. Kills DefaultOrchestrator.onCompleteOutcome:838 RemoveConditional_EQUAL_IF (the status operand): an
    // answer that carries an order but whose status is not completed is not COMPLETED
    @Test
    void anAnswerWithAnOrderButNotCompleted_isNotCompleted() {
        PurchaseIntent after = answering(CheckoutStatus.READY_FOR_COMPLETE, true);

        assertThat(after.state()).isEqualTo(IntentState.RECONCILING);
        assertThat(after.orderId()).isEmpty();
    }

    // Covers: 7.2, 7.4, I1. Kills DefaultOrchestrator.onCompleteOutcome:858 RemoveConditional_EQUAL_IF (the status operand): a
    // decline code on an answer whose session is not ready_for_complete is an error answer, not a decline: the sibling attempt is
    // settled as "not_charged", where a decline settles it as "payment_declined"
    @Test
    void aDeclineCodeOnAnotherStatus_isAnErrorAnswer_notADecline() {
        lab = new Lab();
        RewritingGateway merchant = new RewritingGateway(lab.merchant);
        Lab.Worker w = lab.worker("worker-s", merchant);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(w);
        merchant.rewrite(MerchantCall.COMPLETE, as(CheckoutStatus.CANCELED, false, error("payment_declined")));

        assertThat(lab.drive(w, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        assertThat(completes(id).get(0).responseJson().orElseThrow()).contains("\"observed\":\"not_charged\"");
    }

    // Covers: 7.5, 7.6, I1, I7. Kills DefaultOrchestrator.completing:934 RemoveConditional_EQUAL_IF (the version operand) and
    // :942 transitionEvent: a resend that one process approved is not sent by it after another process has moved the
    // intent since: the marker belongs to one version of the intent. Each hand-over back to RECONCILING is recorded
    @Test
    void aStaleResendMarker_isNotObeyed() {
        lab = new Lab();
        Lab.Worker a = lab.worker("worker-a2");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(a);
        advanceUntilCompleting(a, id);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker b = lab.worker("worker-b2");
        b.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        advanceUntilCompleting(b, id);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        a.orchestrator().advance(id);

        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).as("a stale approval sends nothing").isZero();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events(id, "resend_not_approved_here")).hasSize(2);
        assertThat(events(id, "transition").stream().filter(e -> e.contains("\"from\":\"COMPLETING\"")
                && e.contains("\"to\":\"RECONCILING\"")).count()).isEqualTo(3);
    }

    private void advanceUntilCompleting(Lab.Worker w, IntentId id) {
        for (int i = 0; i < 40 && lab.intent(id).state() != IntentState.COMPLETING; i++) {
            lab.waitUntilNextAttempt(w.orchestrator().advance(id));
        }
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETING);
    }

    // Covers: 7.4, I7. Kills DefaultOrchestrator.lambda$resend$28:955 and lambda$resend$29:957 NullReturnVals (NO_COVERAGE): a resend
    // needs the stored body and the stored key of the attempt it repeats (I7): a complete attempt that has lost either is a
    // corrupt row, refused loudly, and nothing is sent
    @Test
    void aResendWithoutItsStoredBodyOrKey_isRefused() {
        for (boolean noBody : List.of(true, false)) {
            lab = new Lab();
            Lab.Worker w = lab.worker("worker-r");
            IntentId id = approved(w);
            PurchaseIntent claimed = lab.intents.claim(id, "worker-r", lab.config.lease()).orElseThrow();
            PurchaseIntent reconciling = claimed.withState(IntentState.RECONCILING, lab.clock.instant())
                    .withUncertainSince(lab.clock.instant().minusSeconds(30));
            IdempotencyKey key = IdempotencyKey.derive(id, MutatingOp.COMPLETE, 0);
            MerchantAttempt stored = new MerchantAttempt(new dev.ucplab.core.intent.AttemptId("c-lost"), id, AttemptOp.COMPLETE, 0,
                    noBody ? Optional.of(key) : Optional.empty(), Optional.empty(),
                    noBody ? Optional.empty() : Optional.of(new FrozenBody("{\"payment\":{}}")), AttemptStatus.UNCERTAIN,
                    Optional.empty(), Optional.empty(), lab.clock.instant().minusSeconds(30), Optional.empty());
            lab.intents.recordAttempt(stored, reconciling, claimed.version(), "worker-r");
            w.orchestrator().advance(id);
            assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETING);

            assertThatThrownBy(() -> w.orchestrator().advance(id)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(noBody ? "complete attempt without a stored body" : "complete attempt without a key");
            assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        }
    }

    // ---------------------------------------------------------------- an approval that expires

    // Covers: 7.6, 9.2, I5. Kills DefaultOrchestrator.expireApproval:760, :761 (NullReturnVals, NO_COVERAGE), :764 (both
    // EQUAL_IF) and :773 transitionEvent: an intent awaiting approval is rejected as approval_expired when its newest approval is
    // past its time or already EXPIRED, with the transition recorded; it is left alone when the approval is still pending, was
    // decided otherwise, or does not exist
    @Test
    void anIntentAwaitingApproval_isExpiredOnlyWhenItsApprovalIs() {
        lab = new Lab();
        Lab.Worker w = lab.worker("worker-e");
        IntentId late = lab.propose(w, TWO_SHIRTS).intentId();
        lab.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        w.orchestrator().advance(late);
        assertThat(lab.intent(late).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(late).failureReason()).contains("approval_expired");
        assertThat(lab.approvals.findLatestForSystem(late).orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(events(late, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"EXPIRED\"")
                .contains("\"to\":\"REJECTED\""));

        lab = new Lab();
        Lab.Worker w2 = lab.worker("worker-e");
        IntentId marked = lab.propose(w2, TWO_SHIRTS).intentId();
        lab.approvals.transition(lab.approvals.findLatestForSystem(marked).orElseThrow().id(), ApprovalStatus.PENDING,
                ApprovalStatus.EXPIRED, lab.clock.instant(), Optional.empty());
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        w2.orchestrator().advance(marked);
        assertThat(lab.intent(marked).failureReason()).contains("approval_expired");

        lab = new Lab();
        Lab.Worker w3 = lab.worker("worker-e");
        IntentId waiting = lab.propose(w3, TWO_SHIRTS).intentId();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        StepResult idle = w3.orchestrator().advance(waiting);
        assertThat(idle.idle()).isTrue();
        assertThat(lab.intent(waiting).state()).isEqualTo(IntentState.AWAITING_APPROVAL);

        lab = new Lab();
        Lab.Worker w4 = lab.worker("worker-e");
        IntentId decided = lab.propose(w4, TWO_SHIRTS).intentId();
        lab.approvals.transition(lab.approvals.findLatestForSystem(decided).orElseThrow().id(), ApprovalStatus.PENDING,
                ApprovalStatus.REJECTED, lab.clock.instant(), Optional.of(Lab.ALICE));
        lab.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(w4.orchestrator().advance(decided).idle()).isTrue();
        assertThat(lab.intent(decided).state()).isEqualTo(IntentState.AWAITING_APPROVAL);

        lab = new Lab();
        Lab.Worker w5 = lab.worker("worker-e");
        IntentId bare = new IntentId("bare");
        lab.intents.insert(PurchaseIntent.proposed(bare, Lab.ALICE, Lab.SHOP, "bare-key", TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.AWAITING_APPROVAL, lab.clock.instant()));
        assertThat(w5.orchestrator().advance(bare).idle()).isTrue();
        assertThat(lab.intent(bare).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
    }

    // Covers: 8.4. Kills DefaultOrchestrator.lastQuoteShape:1342 ConditionalsBoundary / RemoveConditional_ORDER_IF and :1344
    // RemoveConditional_EQUAL_IF: an update carries the line ids and the buyer of the newest stored response that has them,
    // looking back past newer attempts that have none (a complete that was never sent), as far as the first create
    @Test
    void anUpdate_carriesTheLineIdsOfTheNewestResponseThatHasThem() {
        lab = new Lab();
        RewritingGateway merchant = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                as(CheckoutStatus.INCOMPLETE, false));
        Lab.Worker w = lab.worker("worker-u", merchant);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = approved(w);
        w.orchestrator().advance(id);
        lab.clock.advance(lab.config.resendQuietPeriod());
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);

        w.orchestrator().advance(id);

        MerchantAttempt update = lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.UPDATE).findFirst().orElseThrow();
        assertThat(update.requestBody().orElseThrow().json()).contains("\"id\":\"li_1\"").contains("\"email\":\"alice@example.com\"");
    }
}
