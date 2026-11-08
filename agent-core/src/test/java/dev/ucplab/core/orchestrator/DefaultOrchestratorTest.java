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
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.fake.CallRecord;
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
        lab.clock.advance(lab.config.resendQuietPeriod().minusSeconds(1));
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

    // Covers: 7.4 new-key guard, I5, I7, P7 (found by the I5 property: the first complete was uncertain, its resend
    // under the same key got price_changed; the key is settled, so the re-approval's new key may be sent)
    @Test
    void sendComplete_sendsNewKey_whenResendUnderEarlierKeyGotDefinitiveAnswer() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.PriceChange("item_123", 2600)));

        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(lab.config.resendQuietPeriod());
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        assertThat(lab.drive(w, id)).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).singleElement()
                .satisfies(o -> assertThat(o.totalMinor()).isEqualTo(5616));
        assertThat(attempts(id, AttemptOp.COMPLETE)).extracting(a -> a.idempotencyKey().orElseThrow())
                .containsExactly(IdempotencyKey.derive(id, MutatingOp.COMPLETE, 0),
                        IdempotencyKey.derive(id, MutatingOp.COMPLETE, 0),
                        IdempotencyKey.derive(id, MutatingOp.COMPLETE, 1));
    }

    // Covers: 7.5 rule 1 (the deadline is per complete key), I5, I1 (found by the property tests in 46 of 100,000
    // schedules: the first key's uncertainty is followed by a re-quote and a re-approval more than 60 s later; the
    // second key's complete is held at the proxy, and with the first key's uncertain_since the intent escalated at
    // once although the held complete then created the order)
    @Test
    void reconcile_startsFreshDeadline_forNewCompleteKeyAfterReQuote() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.DelayForward(Duration.ofSeconds(15))));
        Instant firstUncertainty = lab.clock.instant();

        assertThat(lab.drive(w, id)).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
        lab.clock.advance(Duration.ofSeconds(50));
        assertThat(lab.clock.instant()).isAfter(firstUncertainty.plus(lab.config.reconcileDeadline()));
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        Instant secondKeySent = lab.clock.instant();

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).singleElement()
                .satisfies(o -> assertThat(o.totalMinor()).isEqualTo(5616));
        assertThat(lab.intent(id).uncertainSince()).contains(secondKeySent);
        assertThat(lab.clock.instant()).isBefore(secondKeySent.plus(lab.config.reconcileDeadline()));
    }

    // Covers: 7.2 (defensive QUOTING row: an update shows the session completed -> RECONCILING), I1, I2 (the order is
    // recorded through reconciliation, nothing new is sent, and nothing throws)
    @Test
    void requote_movesToReconcilingAndRecordsOrder_whenUpdateShowsSessionCompleted() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
        // A late complete lands on the session after the re-quote began (here sent outside the orchestrator).
        lab.merchant.setPrice("item_123", 2500);
        MerchantAttempt first = attempts(id, AttemptOp.COMPLETE).get(0);
        SessionId session = lab.intent(id).sessionId().orElseThrow();
        assertThat(lab.merchant.complete(session, first.requestBody().orElseThrow(),
                IdempotencyKey.derive(id, MutatingOp.COMPLETE, 5))).isInstanceOf(CallOutcome.Ok.class);
        assertThat(lab.merchant.orders()).hasSize(1);
        int completesDelivered = lab.merchant.delivered(MerchantCall.COMPLETE);

        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        assertThat(attempts(id, AttemptOp.UPDATE)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED));
        assertThat(lab.intent(id).uncertainSince()).isPresent();
        assertThat(lab.intents.events(id)).filteredOn(e -> e.type().equals("transition"))
                .extracting(e -> e.payloadJson())
                .anySatisfy(p -> assertThat(p).contains("\"from\":\"QUOTING\"").contains("\"event\":\"OBSERVED_COMPLETED\"")
                        .contains("\"to\":\"RECONCILING\""));

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.intent(id).orderId()).contains(lab.merchant.orders().get(0).orderId());
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(completesDelivered);
        assertThat(attempts(id, AttemptOp.COMPLETE)).hasSize(1);
    }

    // Covers: 7.2 (defensive QUOTING row: an update shows the session complete_in_progress -> RECONCILING; RECONCILING
    // complete_in_progress -> poll again), I1, I2 (one order, nothing new is sent)
    @Test
    void requote_movesToReconcilingAndPolls_whenUpdateShowsCompleteInProgress() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.HoldInProgress(Duration.ofSeconds(15))));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(IntentState.QUOTING);
        // A late complete reaches the session after the re-quote began and is still charging (sent outside the
        // orchestrator).
        lab.merchant.setPrice("item_123", 2500);
        MerchantAttempt first = attempts(id, AttemptOp.COMPLETE).get(0);
        SessionId session = lab.intent(id).sessionId().orElseThrow();
        assertThat(lab.merchant.complete(session, first.requestBody().orElseThrow(),
                IdempotencyKey.derive(id, MutatingOp.COMPLETE, 5))).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(lab.merchant.session(session).orElseThrow().status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        int completesDelivered = lab.merchant.delivered(MerchantCall.COMPLETE);

        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        assertThat(attempts(id, AttemptOp.UPDATE)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED));
        assertThat(lab.intent(id).uncertainSince()).isPresent();
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        assertThat(lab.intents.events(id)).filteredOn(e -> e.type().equals("transition"))
                .extracting(e -> e.payloadJson())
                .anySatisfy(p -> assertThat(p).contains("\"from\":\"QUOTING\"")
                        .contains("\"event\":\"OBSERVED_COMPLETE_IN_PROGRESS\"").contains("\"to\":\"RECONCILING\""))
                .anySatisfy(p -> assertThat(p).contains("\"from\":\"RECONCILING\"")
                        .contains("\"event\":\"OBSERVED_COMPLETE_IN_PROGRESS\"").contains("\"to\":\"RECONCILING\""));
        assertThat(lab.merchant.orders()).isEmpty();

        lab.clock.advance(Duration.ofSeconds(15));
        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).singleElement()
                .satisfies(o -> assertThat(lab.intent(id).orderId()).contains(o.orderId()));
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(completesDelivered);
        assertThat(attempts(id, AttemptOp.COMPLETE)).hasSize(1);
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

    // Covers: 7.5 (maxCompleteResends), I5 (with resends left over before a longer deadline, the cap stops further
    // completes: it records resends_exhausted, polls with GET only, then escalates at the deadline with no order)
    @Test
    void reconcile_stopsAfterMaxResends_pollsUntilLongerDeadline() {
        OrchestratorConfig d = OrchestratorConfig.defaults();
        Lab long120 = new Lab(new OrchestratorConfig(d.lease(), d.completeRequestTimeout(), d.resendQuietPeriod(),
                Duration.ofSeconds(120), d.maxCompleteResends(), d.maxQuoteRetries(), d.pollInitial(), d.pollMax(),
                d.pollJitter()));
        Lab.Worker w120 = long120.worker("worker-a");
        IntentId id = long120.propose(w120, TWO_SHIRTS).intentId();
        assertThat(long120.decide(w120, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        long120.merchant.addRule(FaultRule.every(MerchantCall.COMPLETE, new Fault.NotSent()));

        assertThat(long120.drive(w120, id, 1000)).isEqualTo(ESCALATED);

        assertThat(long120.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.COMPLETE)
                .hasSize(1 + d.maxCompleteResends());
        Instant exhaustedAt = long120.intents.events(id).stream()
                .filter(e -> e.payloadJson().contains("resends_exhausted")).findFirst().orElseThrow().at();
        List<CallRecord> afterCap = long120.merchant.log().stream().filter(c -> !c.at().isBefore(exhaustedAt)).toList();
        assertThat(afterCap).isNotEmpty().allSatisfy(c -> assertThat(c.call()).isEqualTo(MerchantCall.GET));
        assertThat(long120.merchant.log()).filteredOn(c -> c.call() == MerchantCall.COMPLETE)
                .hasSize(1 + d.maxCompleteResends());
        Instant deadline = long120.intent(id).uncertainSince().orElseThrow().plus(Duration.ofSeconds(120));
        assertThat(long120.clock.instant()).isEqualTo(deadline);
        assertThat(long120.intent(id).failureReason()).contains(DefaultOrchestrator.OUTCOME_UNKNOWN);
        assertThat(long120.merchant.delivered(MerchantCall.COMPLETE)).isZero();
        assertThat(long120.merchant.orders()).isEmpty();
    }

    // Covers: 7.5 "Reconciliation defaults", I5 (with the defaults, a merchant that stays ready_for_complete sees
    // exactly 2 resends, at the quiet-period spacing, and the intent escalates at the deadline)
    @Test
    void reconcile_sendsTwoResendsThenEscalatesAtDeadline_withDefaults() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.every(MerchantCall.COMPLETE, new Fault.NotSent()));

        assertThat(lab.drive(w, id)).isEqualTo(ESCALATED);

        assertThat(lab.config.maxCompleteResends()).isEqualTo(2);
        List<MerchantAttempt> completes = attempts(id, AttemptOp.COMPLETE);
        assertThat(completes).hasSize(1 + lab.config.maxCompleteResends());
        for (int i = 1; i < completes.size(); i++) {
            assertThat(Duration.between(completes.get(i - 1).sentAt(), completes.get(i).sentAt()))
                    .isGreaterThanOrEqualTo(lab.config.resendQuietPeriod());
        }
        assertThat(lab.intents.events(id)).filteredOn(e -> e.type().equals("reconcile_read"))
                .allSatisfy(e -> assertThat(e.payloadJson()).contains("\"status\":\"ready_for_complete\""));
        // The third resend would fall due at the deadline itself, so the deadline escalates first.
        Instant deadline = lab.intent(id).uncertainSince().orElseThrow().plus(lab.config.reconcileDeadline());
        assertThat(completes).allSatisfy(a -> assertThat(a.sentAt()).isBefore(deadline));
        assertThat(lab.clock.instant()).isEqualTo(deadline);
        assertThat(lab.intent(id).failureReason()).contains(DefaultOrchestrator.OUTCOME_UNKNOWN);
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

    // Covers: 7.5 "Who waits" (advance never sleeps; it returns when to poll, backed off from uncertain_since,
    // within jitter, never past the deadline; an approved resend is due now; idle steps carry no time)
    @Test
    void advance_returnsNextAttemptAt_forPollsAndResends() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        Duration initial = lab.config.pollInitial();
        Duration max = lab.config.pollMax();
        double j = lab.config.pollJitter();

        StepResult first = w.orchestrator().advance(id);
        assertThat(first.after()).isEqualTo(RECONCILING);
        assertThat(first.nextAttemptAt()).hasValueSatisfying(t -> assertThat(Duration.between(lab.clock.instant(), t))
                .isBetween(scaled(initial, 1 - j), scaled(initial, 1 + j)));

        lab.clock.advance(Duration.ofSeconds(5));
        StepResult later = w.orchestrator().advance(id);
        assertThat(later.after()).isEqualTo(RECONCILING);
        assertThat(later.nextAttemptAt()).hasValueSatisfying(t -> assertThat(Duration.between(lab.clock.instant(), t))
                .isBetween(scaled(max, 1 - j), scaled(max, 1 + j)));

        lab.clock.advance(lab.config.resendQuietPeriod());
        StepResult resendOwed = w.orchestrator().advance(id);
        assertThat(resendOwed.after()).isEqualTo(COMPLETING);
        assertThat(resendOwed.nextAttemptAt()).contains(lab.clock.instant());

        StepResult done = w.orchestrator().advance(id);
        assertThat(done.after()).isEqualTo(COMPLETED);
        assertThat(done.nextAttemptAt()).isEmpty();
        assertThat(w.orchestrator().advance(id).nextAttemptAt()).isEmpty();
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.5 (the next poll is never scheduled past the reconciliation deadline)
    @Test
    void advance_capsNextAttemptAt_atDeadline() {
        IntentId id = approved();
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        lab.merchant.addRule(FaultRule.every(MerchantCall.GET, new Fault.Reject(503, "unavailable", Optional.empty())));
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        Instant deadline = lab.intent(id).uncertainSince().orElseThrow().plus(lab.config.reconcileDeadline());

        lab.clock.advance(lab.config.reconcileDeadline().minusMillis(100));
        StepResult r = w.orchestrator().advance(id);

        assertThat(r.after()).isEqualTo(RECONCILING);
        assertThat(r.nextAttemptAt()).contains(deadline);
    }

    private static Duration scaled(Duration d, double factor) {
        return Duration.ofNanos(Math.round(d.toNanos() * factor));
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

        Duration poll = Duration.ofMillis(250);
        Duration pollMax = Duration.ofSeconds(2);

        assertThatThrownBy(() -> new OrchestratorConfig(Duration.ofSeconds(30), ten, Duration.ofSeconds(9), minute, 3, 3,
                poll, pollMax, 0.2)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.5");
        assertThatThrownBy(() -> new OrchestratorConfig(Duration.ofSeconds(24), ten, ten, minute, 3, 3,
                poll, pollMax, 0.2)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.6");
        assertThatThrownBy(() -> new OrchestratorConfig(Duration.ofSeconds(30), ten, ten, minute, 3, 3,
                pollMax, poll, 0.2)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.5");
        assertThat(OrchestratorConfig.defaults().lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(OrchestratorConfig.defaults().resendQuietPeriod()).isEqualTo(Duration.ofSeconds(20));
    }

    // Covers: 7.5 "Reconciliation defaults" (the defaults pass validation and allow exactly their resend cap)
    @Test
    void config_defaultsPassValidation() {
        OrchestratorConfig d = OrchestratorConfig.defaults();

        OrchestratorConfig rebuilt = new OrchestratorConfig(d.lease(), d.completeRequestTimeout(), d.resendQuietPeriod(),
                d.reconcileDeadline(), d.maxCompleteResends(), d.maxQuoteRetries(), d.pollInitial(), d.pollMax(),
                d.pollJitter());

        assertThat(rebuilt).isEqualTo(d);
        assertThat(d.maxCompleteResends()).isEqualTo(2);
        assertThat(d.reconcileDeadline()).isGreaterThan(d.resendQuietPeriod().multipliedBy(d.maxCompleteResends()));
    }

    // Covers: 7.5 "Reconciliation defaults" (a resend cap the deadline cannot reach fails startup validation)
    @Test
    void config_rejectsUnreachableResendCap() {
        OrchestratorConfig d = OrchestratorConfig.defaults();

        assertThatThrownBy(() -> new OrchestratorConfig(d.lease(), d.completeRequestTimeout(), d.resendQuietPeriod(),
                d.reconcileDeadline(), 3, d.maxQuoteRetries(), d.pollInitial(), d.pollMax(), d.pollJitter()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("7.5")
                .hasMessageContaining("resend cap");
        assertThatThrownBy(() -> new OrchestratorConfig(d.lease(), d.completeRequestTimeout(), d.resendQuietPeriod(),
                Duration.ofSeconds(40), 2, d.maxQuoteRetries(), d.pollInitial(), d.pollMax(), d.pollJitter()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resend cap");
    }
}
