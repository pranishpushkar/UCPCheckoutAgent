package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.intent.IntentState.REJECTED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.merchant.fake.ModelOrder;
import dev.ucplab.core.policy.RequotePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Example-based runs of the scenario catalogue (13.2) against FaultyMerchantModel. Each test asserts on the model's
 * orders, the ground truth (13.1), before it looks at the intent.
 */
class ScenarioTest {

    private final Lab lab = new Lab();
    private final Lab.Worker worker = lab.worker("worker-a");

    private IntentId proposeAndApprove(Lab.Worker w) {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        return id;
    }

    private List<MerchantAttempt> completes(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    /** I4: one session was ever created, it is the intent's session, and any order is on it. */
    private void assertOneSession(IntentId id) {
        SessionId session = lab.intent(id).sessionId().orElseThrow();
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
        assertThat(lab.merchant.orders()).allSatisfy(o -> assertThat(o.sessionId()).isEqualTo(session));
    }

    /** I7: every complete attempt under one approval uses that approval's key and the identical stored body. */
    private void assertResendsReuseKeyAndBody(IntentId id) {
        List<MerchantAttempt> completes = completes(id);
        assertThat(completes).isNotEmpty();
        for (MerchantAttempt a : completes) {
            MerchantAttempt first = completes.stream().filter(c -> c.revision() == a.revision()).findFirst().orElseThrow();
            assertThat(a.idempotencyKey()).isEqualTo(first.idempotencyKey());
            assertThat(a.requestBody().orElseThrow().bytes()).isEqualTo(first.requestBody().orElseThrow().bytes());
        }
    }

    // ---------------------------------------------------------------- S0

    // Covers: S0, I1, I2, I3, G1, FR4 (no fault: one order matching the approved snapshot)
    @Test
    void scenarioS0_completesWithOneMatchingOrder_withNoFault() {
        IntentId id = proposeAndApprove(worker);

        assertThat(lab.drive(worker, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        ModelOrder order = lab.merchant.orders().get(0);
        assertThat(order.totalMinor()).isEqualTo(5400);
        assertThat(lab.intent(id).orderId()).contains(order.orderId());
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(lab.intents.events(id)).filteredOn(e -> e.type().equals("transition"))
                .extracting(e -> e.payloadJson().replaceAll(".*\"to\":\"([A-Z_]+)\".*", "$1"))
                .containsSubsequence("QUOTING", "AWAITING_APPROVAL", "APPROVED", "COMPLETING", "COMPLETED");
    }

    // ---------------------------------------------------------------- S2, S3

    // Covers: S2, I1, I4, I7 (the complete response is dropped after the merchant processed it)
    @Test
    void scenarioS2_completesWithOneOrder_whenCompleteResponseIsDropped() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));

        assertThat(lab.drive(worker, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertOneSession(id);
        assertResendsReuseKeyAndBody(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.intent(id).orderId()).contains(lab.merchant.orders().get(0).orderId());
    }

    // Covers: S3, I1, I7 (the complete response arrives after the client timeout)
    @Test
    void scenarioS3_completesWithOneOrder_whenCompleteResponseIsDelayed() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayResponse()));

        assertThat(lab.drive(worker, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertResendsReuseKeyAndBody(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
    }

    // Covers: S3, I1, I7, 7.2 (observed complete_in_progress: poll, do not resend, then completed)
    @Test
    void scenarioS3_pollsThroughCompleteInProgress_whenMerchantChargeIsSlow() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.HoldInProgress(Duration.ofSeconds(15))));

        assertThat(lab.drive(worker, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(completes(id)).hasSize(1);
        assertThat(lab.intents.events(id)).anySatisfy(e -> assertThat(e.payloadJson())
                .contains("\"event\":\"OBSERVED_COMPLETE_IN_PROGRESS\""));
    }

    // ---------------------------------------------------------------- S5

    // Covers: S5, P7, I2, I3, I7 (price changes before complete: re-approval, then one order at the new approved total)
    @Test
    void scenarioS5_reapprovesThenCompletes_whenPriceChangesBetweenQuoteAndComplete() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.setPrice("item_123", 2600);

        assertThat(lab.drive(worker, id)).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.pending(worker, id).snapshot().totalMinor()).isEqualTo(5616);

        assertThat(lab.decide(worker, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.drive(worker, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).singleElement()
                .satisfies(o -> assertThat(o.totalMinor()).isEqualTo(5616));
        lab.assertOrderMatchesLatestApproval(id);
        assertOneSession(id);
        List<ApprovalRecord> approvals = lab.approvals.listForSystem(id);
        assertThat(approvals).hasSize(2).allSatisfy(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.CONSUMED));
        assertThat(completes(id)).extracting(MerchantAttempt::revision).containsExactly(0, 1);
        assertThat(completes(id).get(0).idempotencyKey()).isNotEqualTo(completes(id).get(1).idempotencyKey());
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
    }

    // Covers: S5, P7, I2, I3 (the approver declines the re-quote: no order, REJECTED, session canceled)
    @Test
    void scenarioS5_endsRejectedWithoutOrder_whenApproverDeclinesReQuote() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.setPrice("item_123", 2600);
        assertThat(lab.drive(worker, id)).isEqualTo(AWAITING_APPROVAL);

        assertThat(lab.decide(worker, id, false)).isInstanceOf(ApprovalDecision.Rejected.class);
        assertThat(lab.drive(worker, id)).isEqualTo(REJECTED);

        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // ---------------------------------------------------------------- S6, S7

    // Covers: S6, I1, 19 (the item sells out after the quote: FAILED_NOT_CHARGED, no order, no stock taken)
    @Test
    void scenarioS6_failsNotCharged_whenItemSellsOutAfterQuote() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.setStock("item_123", 1);

        assertThat(lab.drive(worker, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.merchant.stock("item_123")).isEqualTo(1);
        // The reference merchant answers complete with status ready_for_complete and an out_of_stock error; the
        // intent is FAILED_NOT_CHARGED with that code, and the session is cancelled.
        assertThat(lab.intent(id).failureReason()).contains("out_of_stock");
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // Covers: S7, I1 (payment declined: FAILED_NOT_CHARGED, no order, stock released, session canceled)
    @Test
    void scenarioS7_failsNotCharged_whenPaymentIsDeclined() {
        lab.payWith(FaultyMerchantModel.TOKEN_DECLINE);
        Lab.Worker declining = lab.worker("worker-decline");
        IntentId id = proposeAndApprove(declining);

        assertThat(lab.drive(declining, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.merchant.stock("item_123")).isEqualTo(100);
        assertThat(lab.intent(id).failureReason()).contains("payment_declined");
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // ---------------------------------------------------------------- S9 (crash and restart)

    // Covers: S9, I1, I4, I5, NFR4 (killed after the merchant processed complete, before the outcome was saved)
    @Test
    void scenarioS9_completesWithOneOrder_whenKilledAfterCompleteWasProcessed() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        Lab.Worker dying = lab.worker("worker-a", gateway);
        IntentId id = proposeAndApprove(dying);
        gateway.crashAfterNext(MerchantCall.COMPLETE);

        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(lab.intent(id).state()).isEqualTo(COMPLETING);

        Lab.Worker restarted = lab.worker("worker-b");
        assertThat(restarted.orchestrator().advance(id).idle()).isTrue(); // the dead worker's lease is still live
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThat(lab.drive(restarted, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertOneSession(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.intent(id).orderId()).contains(lab.merchant.orders().get(0).orderId());
    }

    // Covers: S9, I1, I4, I7, 7.3 (killed after the attempt row was written but before complete was sent)
    @Test
    void scenarioS9_completesWithOneOrder_whenKilledBeforeCompleteWasSent() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        Lab.Worker dying = lab.worker("worker-a", gateway);
        IntentId id = proposeAndApprove(dying);
        gateway.crashBeforeNext(MerchantCall.COMPLETE);

        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(completes(id)).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.SENT));

        Lab.Worker restarted = lab.worker("worker-b");
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThat(lab.drive(restarted, id)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertOneSession(id);
        assertResendsReuseKeyAndBody(id);
        assertThat(completes(id)).hasSize(2);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
    }

    // Covers: S9, I1, I5, 7.5 (killed while RECONCILING; the restart keeps uncertain_since and completes)
    @Test
    void scenarioS9_completesWithOneOrder_whenKilledWhileReconciling() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        Lab.Worker dying = lab.worker("worker-a", gateway);
        IntentId id = proposeAndApprove(dying);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        assertThat(dying.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        PurchaseIntent reconciling = lab.intent(id);
        gateway.crashAfterNext(MerchantCall.GET);

        assertThatThrownBy(() -> dying.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);

        Lab.Worker restarted = lab.worker("worker-b");
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThat(restarted.orchestrator().advance(id).after()).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertOneSession(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.intent(id).uncertainSince()).isEqualTo(reconciling.uncertainSince());
    }

    // ---------------------------------------------------------------- S16

    // Covers: S16, P8, I1, I2, I3, I5, I7 (the price changes before every complete; the approver approves every quote)
    @Test
    void scenarioS16_rejectsPriceUnstableAfterTwoReQuotes_whenPriceChangesBeforeEveryComplete() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.PriceChange("item_123", 2700)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 3, new Fault.PriceChange("item_123", 2800)));
        IntentId id = lab.propose(worker, TWO_SHIRTS).intentId();

        IntentState state = IntentState.AWAITING_APPROVAL;
        int approvalsGiven = 0;
        while (state == AWAITING_APPROVAL) {
            assertThat(lab.decide(worker, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
            approvalsGiven++;
            state = lab.drive(worker, id);
        }

        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(state).isEqualTo(REJECTED);
        assertThat(approvalsGiven).isEqualTo(3);
        assertThat(lab.approvals.listForSystem(id)).hasSize(3)
                .allSatisfy(a -> assertThat(a.status()).isEqualTo(ApprovalStatus.CONSUMED));
        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.failureReason()).contains(RequotePolicy.PRICE_UNSTABLE);
        assertThat(intent.requoteCount()).isEqualTo(2);
        assertThat(completes(id)).extracting(MerchantAttempt::revision).containsExactly(0, 1, 2);
        assertThat(completes(id)).extracting(a -> a.idempotencyKey().orElseThrow()).doesNotHaveDuplicates();
        assertOneSession(id);
        assertThat(lab.merchant.session(intent.sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // ---------------------------------------------------------------- S17

    // Covers: S17, I1, I7, 7.5 (complete held at the proxy 12 s: past the 10 s complete timeout, inside the 20 s quiet
    // period; GET shows ready_for_complete; no resend inside the quiet period; COMPLETED with exactly one order)
    @Test
    void scenarioS17_doesNotResendInsideQuietPeriod_whenCompleteIsHeldAtProxy() {
        IntentId id = proposeAndApprove(worker);
        Duration hold = Duration.ofSeconds(12);
        assertThat(hold).isGreaterThan(lab.config.completeRequestTimeout()).isLessThan(lab.config.resendQuietPeriod());
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayForward(hold)));

        StepResult step = worker.orchestrator().advance(id);
        assertThat(step.after()).isEqualTo(RECONCILING);
        Instant sentAt = completes(id).get(0).sentAt();
        boolean sawReadyForComplete = false;
        while (step.after() == RECONCILING) {
            lab.waitUntilNextAttempt(step);
            step = worker.orchestrator().advance(id);
            assertThat(completes(id)).as("complete attempts at %s", lab.clock.instant()).hasSize(1);
            assertThat(step.after()).isNotEqualTo(COMPLETING);
            sawReadyForComplete |= lab.intents.events(id).stream()
                    .anyMatch(e -> e.payloadJson().contains("\"guard\":\"QUIET_PERIOD_NOT_ELAPSED\""));
        }

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertThat(step.after()).isEqualTo(COMPLETED);
        assertThat(sawReadyForComplete).isTrue();
        assertThat(lab.clock.instant()).isAfterOrEqualTo(sentAt.plus(hold))
                .isBefore(sentAt.plus(lab.config.resendQuietPeriod()));
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(completes(id)).hasSize(1);
    }

    // Covers: S17, I1, I7 (held longer than the quiet period: the resend reuses the key, the late original replays it)
    @Test
    void scenarioS17_createsOneOrder_whenHeldRequestArrivesAfterTheResend() {
        IntentId id = proposeAndApprove(worker);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayForward(Duration.ofSeconds(25))));

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(lab.config.resendQuietPeriod());
        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETED);
        lab.clock.advance(Duration.ofSeconds(5));

        assertThat(lab.merchant.orders()).hasSize(1);
        lab.assertOrderMatchesLatestApproval(id);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(2);
        assertResendsReuseKeyAndBody(id);
        assertThat(completes(id)).extracting(a -> a.idempotencyKey().orElseThrow())
                .containsOnly(IdempotencyKey.derive(id, MutatingOp.COMPLETE, 0));
        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
    }
}
