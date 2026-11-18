package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.HandoffTest.FIVE_MINUTES;
import static dev.ucplab.core.orchestrator.HandoffTest.HANDOFF;
import static dev.ucplab.core.orchestrator.HandoffTest.URL;
import static dev.ucplab.core.orchestrator.HandoffTest.total;
import static dev.ucplab.core.orchestrator.HandoffTest.view;
import static dev.ucplab.core.orchestrator.HandoffTest.withUrl;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.always;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * H5 C7b: a crash at each boundary of the APPROVED step at a handoff merchant (7.2, 7.6, S9). A worker dies at the
 * boundary (its repository throws there), its lease runs out, and a new worker takes the intent over; the outcome is
 * the one a single uninterrupted step would have reached, and nothing is done twice.
 */
class HandoffCrashTest {

    private final Lab lab = new Lab();
    private final CrashPlan<CrashingIntentRepository.Op> intentPlan = new CrashPlan<>(CrashingIntentRepository.Op.class);
    private final CrashPlan<CrashingApprovalRepository.Op> approvalPlan =
            new CrashPlan<>(CrashingApprovalRepository.Op.class);

    HandoffCrashTest() {
        lab.entries = id -> id.equals(SHOP) ? Optional.of(HANDOFF) : Optional.empty();
    }

    /** A worker whose repositories crash where the plans say. */
    private Lab.Worker dying(MerchantGateway gateway) {
        return lab.worker("dying", gateway, new CrashingApprovalRepository(lab.approvals, approvalPlan),
                new CrashingIntentRepository(lab.intents, intentPlan));
    }

    /** The worker that takes over once the dying one's lease has run out. */
    private Lab.Worker takeOver(MerchantGateway gateway) {
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return lab.worker("next", gateway);
    }

    private IntentId approved(Lab.Worker worker) {
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        lab.decide(worker, id, true);
        return id;
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private void crashes(Lab.Worker worker, IntentId id) {
        assertThatThrownBy(() -> worker.orchestrator().advance(id))
                .isInstanceOf(InterceptingGateway.SimulatedCrash.class);
    }

    private static UnaryOperator<CallOutcome<CheckoutView>> uncertain() {
        return always(new CallOutcome.Uncertain<>("timeout"));
    }

    // Covers: 7.2 hand_off, S9 (a crash after the GET and before the HANDED_OFF write: the next worker reads again and
    // hands off once)
    @Test
    void aCrashBeforeTheHandedOffWrite_handsOffOnceAfterTakeOver() {
        MerchantGateway gateway = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL));
        Lab.Worker worker = dying(gateway);
        IntentId id = approved(worker);
        intentPlan.armNext(CrashingIntentRepository.Op.UPDATE, CrashPlan.Phase.BEFORE);

        crashes(worker, id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.intent(id).handoffUrl()).isEmpty();

        assertThat(lab.drive(takeOver(gateway), id)).isEqualTo(IntentState.HANDED_OFF);
        assertThat(events(id, "handed_off")).hasSize(1);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.CONSUMED);
    }

    // Covers: 7.2 hand_off, I3, S9 (a crash after the HANDED_OFF write and before the approval is consumed: the intent is
    // terminal with its URL, the approval stays APPROVED, and no later pass sends anything or completes)
    @Test
    void aCrashBeforeTheConsume_leavesATerminalIntent_thatNothingCompletes() {
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        Lab.Worker worker = dying(recording);
        IntentId id = approved(worker);
        intentPlan.armNext(CrashingIntentRepository.Op.UPDATE, CrashPlan.Phase.AFTER);

        crashes(worker, id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.HANDED_OFF);
        assertThat(lab.intent(id).handoffUrl()).isPresent();
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
        int before = recording.calls().size();

        assertThat(lab.drive(takeOver(recording), id)).isEqualTo(IntentState.HANDED_OFF);
        assertThat(recording.calls().subList(before, recording.calls().size())).isEmpty();
        assertThat(lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.COMPLETE);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.2 handoff_timeout, 7.6, S9 (a PENDING approval is recovered and the worker crashes right after that
    // write, before the GET: the next worker finds it APPROVED and does not recover it again, and the deadline runs
    // from the persisted recovery, not from the take-over)
    @Test
    void aCrashAfterThePendingRecovery_keepsItsDecisionTime() {
        MerchantGateway gateway = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, uncertain());
        Lab.Worker worker = dying(gateway);
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        approvalPlan.armNext(CrashingApprovalRepository.Op.TRANSITION, CrashPlan.Phase.BEFORE);
        assertThatThrownBy(() -> lab.decide(worker, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        Lab.Worker recovering = takeOverDying(gateway);
        Instant recoveredAt = lab.clock.instant();
        approvalPlan.armNext(CrashingApprovalRepository.Op.TRANSITION, CrashPlan.Phase.AFTER);

        crashes(recovering, id);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().decidedAt()).contains(recoveredAt);

        Lab.Worker next = takeOver(gateway);
        next.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(events(id, "approval_decision_recovered")).as("the APPROVED approval is not recovered again").isEmpty();
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().decidedAt()).contains(recoveredAt);
        lab.clock.advance(Duration.between(lab.clock.instant(), recoveredAt.plus(FIVE_MINUTES)));
        next.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("handoff_timeout");
    }

    /** A second dying worker, over the same plans, once the first one's lease has run out. */
    private Lab.Worker takeOverDying(MerchantGateway gateway) {
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return lab.worker("dying-2", gateway, new CrashingApprovalRepository(lab.approvals, approvalPlan),
                new CrashingIntentRepository(lab.intents, intentPlan));
    }

    // Covers: 7.2 handoff_timeout, 7.6, S9 (a crash before the timeout write leaves the intent APPROVED past its
    // deadline, and the next worker ends it at once; a crash after it leaves REJECTED and cancel_pending together, and
    // the next worker sends the owed cancel once)
    @Test
    void aCrashAtTheTimeoutWrite_endsRejectedWithItsCancelOwed() {
        for (CrashPlan.Phase phase : CrashPlan.Phase.values()) {
            HandoffCrashTest t = new HandoffCrashTest();
            MerchantGateway gateway = new RewritingGateway(t.lab.merchant).rewrite(MerchantCall.GET, uncertain());
            Lab.Worker worker = t.dying(gateway);
            IntentId id = t.approved(worker);
            t.lab.clock.advance(FIVE_MINUTES);
            t.intentPlan.armNext(CrashingIntentRepository.Op.UPDATE, phase);

            t.crashes(worker, id);
            if (phase == CrashPlan.Phase.BEFORE) {
                assertThat(t.lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
            } else {
                assertThat(t.lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
                assertThat(t.lab.intent(id).cancelPending()).as("in the same write").isTrue();
            }

            assertThat(t.lab.drive(t.takeOver(gateway), id)).as(phase.name()).isEqualTo(IntentState.REJECTED);
            assertThat(t.lab.intent(id).failureReason()).contains("handoff_timeout");
            assertThat(t.lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
            assertThat(t.lab.intent(id).cancelPending()).isFalse();
        }
    }

    // Covers: 7.2 hand_off_changed, 7.3 step 3, S9 (the re-quote's get row and the move to QUOTING are one write: a
    // crash before it leaves APPROVED and no get row, and the next worker reads again and re-quotes; a crash after it
    // leaves QUOTING with its get row, and the next worker sends the update, the cycle starting at that row)
    @Test
    void aCrashAtTheRequoteWrite_requotesOnce() {
        for (CrashPlan.Phase phase : CrashPlan.Phase.values()) {
            HandoffCrashTest t = new HandoffCrashTest();
            MerchantGateway gateway = new RewritingGateway(t.lab.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), v -> total(v, v.totalMinor() + 1)));
            Lab.Worker worker = t.dying(gateway);
            IntentId id = t.approved(worker);
            t.intentPlan.armNext(CrashingIntentRepository.Op.RECORD_ATTEMPT, phase);

            t.crashes(worker, id);
            if (phase == CrashPlan.Phase.BEFORE) {
                assertThat(t.lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
                assertThat(t.lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.GET);
            } else {
                assertThat(t.lab.intent(id).state()).isEqualTo(IntentState.QUOTING);
                assertThat(t.lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.GET).hasSize(1);
            }

            assertThat(t.lab.drive(t.takeOver(gateway), id)).as(phase.name()).isEqualTo(IntentState.AWAITING_APPROVAL);
            assertThat(t.lab.intents.attempts(id)).extracting(a -> a.op()).as(phase.name())
                    .containsExactly(AttemptOp.CREATE, AttemptOp.GET, AttemptOp.UPDATE);
            assertThat(t.lab.intent(id).requoteCount()).isEqualTo(1);
        }
    }
}
