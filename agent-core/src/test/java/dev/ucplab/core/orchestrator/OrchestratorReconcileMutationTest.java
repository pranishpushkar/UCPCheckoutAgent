package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.RewritingGateway.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation and escalation (7.2, 7.5, 8.1, I1, I3, I5, I7), found unchecked by the H3 P7 mutation pass, groups 3: what
 * each answer to the reconciliation read leads to, inside and after the deadline, and what an escalation records. The first
 * complete is never delivered (NotSent), so the intent reconciles; the read is then answered as the test says.
 */
class OrchestratorReconcileMutationTest {

    private Lab lab;
    private Lab.Worker w;
    private RewritingGateway gateway;
    private IntentId id;

    /** An intent in RECONCILING: its complete was not sent, and the merchant's read answers as {@code reads} says. */
    private void reconciling(Consumer<RewritingGateway> reads) {
        lab = new Lab();
        gateway = new RewritingGateway(lab.merchant);
        reads.accept(gateway);
        w = lab.worker("worker-r", gateway);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
    }

    private List<String> events(String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> completes() {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    private StepResult step() {
        return w.orchestrator().advance(id);
    }

    // Covers: 7.5, 11. Kills DefaultOrchestrator.reconcile:978 event (reconcile_read): every read is recorded with its outcome
    // and the status it showed
    @Test
    void everyReconciliationRead_isRecorded() {
        reconciling(g -> g.rewrite(MerchantCall.GET, as(CheckoutStatus.COMPLETE_IN_PROGRESS, false)));

        step();

        assertThat(events("reconcile_read")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"outcome\":\"ok\"").contains("\"status\":\"complete_in_progress\""));
    }

    // Covers: 7.5, 7.2, I1. Kills DefaultOrchestrator.reconcile:985 RemoveConditional_EQUAL_ELSE, :986 (both, NO_COVERAGE) and
    // :992 transitionEvent: a read that says completed but names no order is not an order: the intent keeps waiting and is
    // escalated at the deadline; one with the order settles the attempts and completes, with its transition recorded
    @Test
    void aReadSayingCompleted_needsTheOrder() {
        reconciling(g -> g.rewrite(MerchantCall.GET, as(CheckoutStatus.COMPLETED, false)));
        step();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events("reconcile_wait")).singleElement().satisfies(e -> assertThat(e).contains("completed_without_order"));
        lab.clock.advance(lab.config.reconcileDeadline().plusSeconds(1));
        step();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
    }

    // Covers: 7.5, 7.2, I1. Kills DefaultOrchestrator.reconcile:984 to :993 (an order observed): the intent completes with the
    // order's id, the unresolved attempt is settled as completed, and the transition is recorded
    @Test
    void aReadShowingTheOrder_completesTheIntent_settlesTheAttempt_andRecordsTheTransition() {
        lab = new Lab();
        w = lab.worker("worker-r");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);

        step();

        PurchaseIntent done = lab.intent(id);
        assertThat(done.state()).isEqualTo(IntentState.COMPLETED);
        assertThat(done.orderId()).contains(lab.merchant.orders().get(0).orderId());
        assertThat(completes()).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED);
            assertThat(a.responseJson().orElseThrow()).contains("\"resolved_by\":\"reconciliation\"")
                    .contains("\"observed\":\"completed\"");
        });
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"OBSERVED_COMPLETED\"")
                .contains("\"to\":\"COMPLETED\""));
    }

    // Covers: 7.5, 7.2. Kills DefaultOrchestrator.reconcile:995 to :1001 (VoidMethodCall settle, update, transitionEvent;
    // NO_COVERAGE): a session the merchant canceled ends FAILED_NOT_CHARGED with the reason canceled_by_merchant, the attempt
    // settled as canceled, and the transition recorded
    @Test
    void aReadShowingACanceledSession_endsNotCharged() {
        reconciling(g -> g.rewrite(MerchantCall.GET, as(CheckoutStatus.CANCELED, false)));

        step();

        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(after.failureReason()).contains("canceled_by_merchant");
        assertThat(completes()).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED);
            assertThat(a.responseJson().orElseThrow()).contains("\"observed\":\"canceled\"");
        });
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"OBSERVED_CANCELED\""));
    }

    // Covers: 7.5, I5. Kills DefaultOrchestrator.reconcile:1003 RemoveConditional_EQUAL_ELSE and :1007 RemoveConditional_EQUAL_IF
    // (a complete in progress is polled again, and an escalation-required read is waited on, until the deadline; after it
    // the intent is ESCALATED with outcome_unknown)
    @Test
    void aChargeInProgressOrAnEscalationRequired_isWaitedOnUntilTheDeadline_thenEscalated() {
        for (CheckoutStatus status : List.of(CheckoutStatus.COMPLETE_IN_PROGRESS, CheckoutStatus.REQUIRES_ESCALATION)) {
            reconciling(g -> g.rewrite(MerchantCall.GET, as(status, false)));

            step();
            assertThat(lab.intent(id).state()).as(status.name()).isEqualTo(IntentState.RECONCILING);
            if (status == CheckoutStatus.COMPLETE_IN_PROGRESS) {
                assertThat(events("transition")).anySatisfy(e -> assertThat(e)
                        .contains("\"event\":\"OBSERVED_COMPLETE_IN_PROGRESS\""));
            } else {
                assertThat(events("reconcile_wait")).singleElement().satisfies(e -> assertThat(e).contains("requires_escalation"));
            }
            lab.clock.advance(lab.config.reconcileDeadline().plusSeconds(1));
            step();

            assertThat(lab.intent(id).state()).as(status.name()).isEqualTo(IntentState.ESCALATED);
            assertThat(lab.intent(id).failureReason()).contains("outcome_unknown");
        }
    }

    // Covers: 7.5, 7.6, I5, 11. Kills DefaultOrchestrator.escalate:1059 EmptyObjectReturnVals and :1062 transitionEvent: an
    // escalation lists every attempt (id, op, revision, status, when sent) in the "escalated" event so a person can look at it,
    // and records the transition
    @Test
    void anEscalation_listsEveryAttempt_andRecordsTheTransition() {
        reconciling(g -> g.rewrite(MerchantCall.GET, always(new CallOutcome.Uncertain<>("down"))));
        lab.clock.advance(lab.config.reconcileDeadline().plusSeconds(1));

        step();

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        MerchantAttempt complete = completes().get(0);
        assertThat(events("escalated")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"attempt_id\":\"" + complete.id().value() + "\"").contains("\"op\":\"COMPLETE\"")
                .contains("\"revision\":0").contains("\"status\":\"UNCERTAIN\"").contains("\"sent_at\":\""));
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"DEADLINE_REACHED\"")
                .contains("\"to\":\"ESCALATED\""));
    }

    private static java.util.function.UnaryOperator<CallOutcome<CheckoutView>> always(CallOutcome<CheckoutView> outcome) {
        return RewritingGateway.always(outcome);
    }

    // Covers: 7.5, 7.2. Kills DefaultOrchestrator.reconcile:1040 BooleanTrueReturnVals / RemoveConditional_EQUAL_IF,
    // :1041 RemoveConditional_EQUAL_IF and :1049 requoteEvents: an incomplete read with an attempt still unresolved waits out
    // the quiet period before it re-quotes (a complete in flight could land after the update); once the quiet period is over
    // the attempt is settled, the intent re-quotes, and the re-quote is recorded
    @Test
    void anIncompleteRead_waitsForTheQuietPeriod_thenRequotes() {
        reconciling(g -> g.rewrite(MerchantCall.GET, as(CheckoutStatus.INCOMPLETE, false)));

        step();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(events("reconcile_wait")).singleElement().satisfies(e -> assertThat(e).contains("incomplete_within_quiet_period"));

        lab.clock.advance(lab.config.resendQuietPeriod());
        step();

        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).isEqualTo(IntentState.QUOTING);
        assertThat(after.requoteCount()).isEqualTo(1);
        assertThat(completes()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.RESPONDED));
        assertThat(events("policy_decision")).anySatisfy(e -> assertThat(e).contains("\"stage\":\"requote\"")
                .contains("\"reason\":\"re-quote 1 of 2\""));
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"OBSERVED_INCOMPLETE\""));
    }

    // Covers: 7.5, I1, I7. Kills DefaultOrchestrator.reconcile:1036 transitionEvent: a ready_for_complete read after the quiet
    // period approves one resend and records the transition with its guard
    @Test
    void aReadyRead_afterTheQuietPeriod_approvesTheResend_andRecordsIt() {
        reconciling(g -> { });
        lab.clock.advance(lab.config.resendQuietPeriod());

        step();

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETING);
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"OBSERVED_READY_FOR_COMPLETE\"")
                .contains("\"guard\":\"RESEND_ALLOWED\"").contains("\"to\":\"COMPLETING\""));
    }

    // Covers: 7.5, 7.6. Kills DefaultOrchestrator.lambda$reconcile$30:970 NullReturnVals (NO_COVERAGE): a RECONCILING intent
    // that has no uncertain_since is a corrupt row, refused loudly
    @Test
    void reconcilingWithoutUncertainSince_isRefused() {
        reconciling(g -> { });
        PurchaseIntent claimed = lab.intents.claim(id, "worker-r", lab.config.lease()).orElseThrow();
        lab.intents.update(claimed.withoutUncertainSince(), claimed.version(), "worker-r");

        assertThatThrownBy(() -> step()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECONCILING without uncertain_since");
    }

    // Covers: 9.2, I3. Kills DefaultOrchestrator.escalateInvalidApproval:1078 to :1084 (the approval_invalid event: the status
    // of the newest approval, NONE when there is none, and whether its hash matches; the escalated event; the transition):
    // an approved intent whose newest approval is no longer usable, or missing, is escalated with those facts, nothing sent
    @Test
    void anUnusableApproval_isEscalated_withTheFactsRecorded() {
        // expired approval with the matching hash
        lab = new Lab();
        w = lab.worker("worker-i");
        id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        String approvalId = lab.approvals.findLatestForSystem(id).orElseThrow().id();
        lab.approvals.transition(approvalId, ApprovalStatus.APPROVED, ApprovalStatus.EXPIRED, lab.clock.instant(),
                java.util.Optional.empty());
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(events("approval_invalid")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"approval_status\":\"EXPIRED\"").contains("\"hash_matches\":true"));
        assertThat(events("escalated")).singleElement().satisfies(e -> assertThat(e).contains("\"op\":\"CREATE\"")
                .contains("\"status\":\"RESPONDED\"").doesNotContain("COMPLETE"));
        assertThat(events("transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"APPROVAL_INVALID\"")
                .contains("\"to\":\"ESCALATED\""));
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();

        // no approval at all, and a hash that differs
        lab = new Lab();
        w = lab.worker("worker-i");
        id = new IntentId("bare");
        lab.intents.insert(PurchaseIntent.proposed(id, Lab.ALICE, Lab.SHOP, "bare-key", TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.APPROVED, lab.clock.instant())
                .withApprovedSnapshotHash(java.util.Optional.of("a-hash")).withSessionId(new dev.ucplab.core.merchant.SessionId("cs_x")));
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(events("approval_invalid")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"approval_status\":\"NONE\"").contains("\"hash_matches\":false"));
    }

    // Covers: 7.5, I1. Kills DefaultOrchestrator.lambda$settle$36:1108 (BooleanTrue, EQUAL_IF): reconciliation settles only the
    // attempts that are still unresolved: one that was answered keeps its own response
    @Test
    void settling_leavesAnAnsweredAttemptAlone() {
        lab = new Lab();
        w = lab.worker("worker-r");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        w.orchestrator().advance(id);
        PurchaseIntent claimed = lab.intents.claim(id, "worker-r", lab.config.lease()).orElseThrow();
        MerchantAttempt answered = new MerchantAttempt(new dev.ucplab.core.intent.AttemptId("answered"), id, AttemptOp.COMPLETE, 0,
                completes().get(0).idempotencyKey(), java.util.Optional.empty(), completes().get(0).requestBody(),
                AttemptStatus.RESPONDED, java.util.Optional.of(200), java.util.Optional.of("{\"mine\":true}"),
                lab.clock.instant(), java.util.Optional.of(lab.clock.instant()));
        lab.intents.recordAttempt(answered, claimed, claimed.version(), "worker-r");

        step();

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETED);
        assertThat(completes()).filteredOn(a -> a.id().value().equals("answered")).singleElement()
                .satisfies(a -> assertThat(a.responseJson()).contains("{\"mine\":true}"));
        assertThat(completes()).filteredOn(a -> !a.id().value().equals("answered")).singleElement()
                .satisfies(a -> assertThat(a.responseJson().orElseThrow()).contains("\"observed\":\"completed\""));
    }
}
