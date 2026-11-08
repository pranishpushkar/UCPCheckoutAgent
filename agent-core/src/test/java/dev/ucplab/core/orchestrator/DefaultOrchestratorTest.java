package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.ESCALATED;
import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.intent.IntentState.REJECTED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultOrchestratorTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    private List<MerchantAttempt> attempts(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private IntentId approved() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        return id;
    }

    // Covers: FR2, 9.2 (the quote and the snapshot come from the merchant's response)
    @Test
    void propose_returnsMerchantQuote_andOpensApprovalBoundToIt() {
        ProposalResult r = lab.propose(w, TWO_SHIRTS);

        assertThat(r.state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(r.quote()).hasValueSatisfying(q -> assertThat(q.totalMinor()).isEqualTo(5400));
        PendingApproval pending = lab.pending(w, r.intentId());
        assertThat(pending.snapshot().totalMinor()).isEqualTo(5400);
        assertThat(pending.snapshot().sessionId()).isEqualTo(r.quote().get().id());
        assertThat(lab.intent(r.intentId()).sessionId()).contains(r.quote().get().id());
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
    }

    // Covers: FR7, P1, S11 (a merchant outside the registry is rejected before any merchant call)
    @Test
    void propose_rejectsUnknownMerchant_withoutContactingAnyMerchant() {
        ProposalResult r = w.orchestrator().propose(lab.alice(), new MerchantId("evil"), TWO_SHIRTS, "buy");

        assertThat(r.state()).isEqualTo(REJECTED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains("policy_P1");
        assertThat(lab.merchant.log()).isEmpty();
        assertThat(lab.drive(w, r.intentId())).isEqualTo(REJECTED);
    }

    // Covers: 7.7, S4, I1 (an identical active proposal returns the existing intent)
    @Test
    void propose_returnsExistingIntent_forIdenticalActiveProposal() {
        ProposalResult first = lab.propose(w, TWO_SHIRTS);

        ProposalResult second = lab.propose(w, List.of(new ProposedLine("item_123", 2)));

        assertThat(second.intentId()).isEqualTo(first.intentId());
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
    }

    // Covers: 7.2 (QUOTING not_sent, retries left), 8.2, S1, I4 (create is retried under the same key; one session)
    @Test
    void propose_retriesCreateUnderSameKey_afterNotSent() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.NotSent()));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 2, new Fault.NotSent()));

        ProposalResult r = lab.propose(w, TWO_SHIRTS);

        assertThat(r.state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
        assertThat(attempts(r.intentId(), AttemptOp.CREATE)).hasSize(3)
                .extracting(a -> a.idempotencyKey().orElseThrow())
                .containsOnly(IdempotencyKey.derive(r.intentId(), MutatingOp.CREATE, 0));
    }

    // Covers: 7.2 (QUOTING retries exhausted), I4 (every resend reuses the key, so the merchant made one session)
    @Test
    void propose_failsNotCharged_whenCreateStaysUncertain() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CREATE, new Fault.CommitThenDrop()));

        ProposalResult r = lab.propose(w, TWO_SHIRTS);

        assertThat(r.state()).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains(DefaultOrchestrator.QUOTE_RETRIES_EXHAUSTED);
        assertThat(attempts(r.intentId(), AttemptOp.CREATE)).hasSize(1 + lab.config.maxQuoteRetries());
        assertThat(lab.merchant.sessionsCreated()).isEqualTo(1);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: P2 (a quote above the per-purchase cap ends the intent without an approval and cancels the session)
    @Test
    void propose_failsNotChargedAndCancels_whenQuoteIsAboveCap() {
        ProposalResult r = lab.propose(w, List.of(new ProposedLine("item_789", 2)));

        assertThat(r.state()).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains("policy_P2");
        assertThat(lab.approvals.listForSystem(r.intentId())).isEmpty();
        assertThat(lab.drive(w, r.intentId())).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.merchant.session(lab.intent(r.intentId()).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
    }

    // Covers: FR3, 7.2 (AWAITING_APPROVAL expired -> REJECTED, then cancel session)
    @Test
    void advance_expiresApprovalAfterTtl_andCancelsSession() {
        ProposalResult r = lab.propose(w, TWO_SHIRTS);
        assertThat(lab.drive(w, r.intentId())).isEqualTo(AWAITING_APPROVAL);

        lab.clock.advance(Duration.ofMinutes(10));

        assertThat(lab.drive(w, r.intentId())).isEqualTo(REJECTED);
        assertThat(lab.intent(r.intentId()).failureReason()).contains(DefaultOrchestrator.APPROVAL_EXPIRED);
        assertThat(lab.approvals.findLatestForSystem(r.intentId()).orElseThrow().status())
                .isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(lab.merchant.session(r.quote().get().id()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: FR3, 7.2 (AWAITING_APPROVAL rejected -> REJECTED; the cancel is the orchestrator's next call)
    @Test
    void reject_movesToRejected_andNextAdvanceCancelsSession() {
        ProposalResult r = lab.propose(w, TWO_SHIRTS);

        assertThat(lab.decide(w, r.intentId(), false)).isInstanceOf(ApprovalDecision.Rejected.class);

        assertThat(lab.intent(r.intentId()).state()).isEqualTo(REJECTED);
        assertThat(lab.drive(w, r.intentId())).isEqualTo(REJECTED);
        assertThat(lab.merchant.session(r.quote().get().id()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(attempts(r.intentId(), AttemptOp.CANCEL)).hasSize(1);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.2 (RECONCILING ready_for_complete), 7.5 (quiet period), I7 (resend: same key, byte-identical body)
    @Test
    void reconcile_resendsUnderSameKeyAndBody_onlyAfterQuietPeriod() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));

        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(Duration.ofSeconds(9));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();

        lab.clock.advance(Duration.ofSeconds(1));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(COMPLETED);

        List<MerchantAttempt> completes = attempts(id, AttemptOp.COMPLETE);
        assertThat(completes).hasSize(2);
        assertThat(completes.get(1).idempotencyKey()).isEqualTo(completes.get(0).idempotencyKey());
        assertThat(completes.get(1).requestBody().orElseThrow().bytes())
                .isEqualTo(completes.get(0).requestBody().orElseThrow().bytes());
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.5 (deadline from uncertain_since -> ESCALATED, never FAILED_NOT_CHARGED), I5 (a restart keeps it)
    @Test
    void reconcile_escalatesAtDeadline_andRestartDoesNotResetIt() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        lab.merchant.addRule(FaultRule.every(MerchantCall.GET, new Fault.Reject(503, "unavailable", Optional.empty())));

        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        Instant since = lab.intent(id).uncertainSince().orElseThrow();
        lab.clock.advance(Duration.ofSeconds(30));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);

        Lab.Worker restarted = lab.worker("worker-b");
        lab.clock.advance(Duration.ofSeconds(31));
        assertThat(restarted.orchestrator().advance(id).after()).isEqualTo(ESCALATED);

        assertThat(lab.intent(id).uncertainSince()).contains(since);
        assertThat(lab.intent(id).failureReason()).contains(DefaultOrchestrator.OUTCOME_UNKNOWN);
        assertThat(lab.intents.events(id)).anySatisfy(e -> assertThat(e.type()).isEqualTo("escalated"));
    }

    // Covers: 7.5 (maxCompleteResends), I5 (with no resends left it polls until the deadline, then ESCALATED)
    @Test
    void reconcile_stopsAfterMaxResends_andEscalatesAtDeadline() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.every(MerchantCall.COMPLETE, new Fault.NotSent()));

        assertThat(lab.drive(w, id)).isEqualTo(ESCALATED);

        assertThat(attempts(id, AttemptOp.COMPLETE)).hasSize(1 + lab.config.maxCompleteResends());
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.6 fencing, S15 (a worker whose lease was taken during its call fails its write; the next one recovers)
    @Test
    void advance_throwsLeaseLost_whenLeaseIsTakenDuringCall_andAnotherWorkerRecovers() {
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        Lab.Worker a = lab.worker("worker-a", gateway);
        Lab.Worker b = lab.worker("worker-b");
        IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
        lab.decide(a, id, true);
        gateway.duringNext(MerchantCall.COMPLETE, () -> {
            lab.clock.advance(lab.config.lease().plusSeconds(1));
            assertThat(lab.intents.claim(id, "worker-b", lab.config.lease())).isPresent();
        });

        assertThatThrownBy(() -> a.orchestrator().advance(id)).isInstanceOf(LeaseLostException.class);

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETING);
        assertThat(attempts(id, AttemptOp.COMPLETE)).singleElement()
                .satisfies(x -> assertThat(x.status()).isEqualTo(AttemptStatus.SENT));
        assertThat(lab.drive(b, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(a.orchestrator().advance(id).idle()).isTrue();
    }

    // Covers: T6 (another user's intent is reported as not found)
    @Test
    void status_reportsNotFound_forAnotherUsersIntent() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        assertThatThrownBy(() -> w.orchestrator().status(lab.bob(), id)).isInstanceOf(IntentNotFoundException.class);
        assertThat(w.orchestrator().status(lab.alice(), id).state()).isEqualTo(AWAITING_APPROVAL);
    }

    // Covers: 7.5, 7.6 (startup validation of the quiet period and the lease)
    @Test
    void config_rejectsShortQuietPeriodAndShortLease() {
        Duration ten = Duration.ofSeconds(10);
        Duration minute = Duration.ofSeconds(60);

        assertThatThrownBy(() -> new OrchestratorConfig(Duration.ofSeconds(30), ten, Duration.ofSeconds(9), minute, 3, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.5");
        assertThatThrownBy(() -> new OrchestratorConfig(Duration.ofSeconds(24), ten, ten, minute, 3, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.6");
        assertThat(OrchestratorConfig.defaults().lease()).isEqualTo(Duration.ofSeconds(30));
    }
}
