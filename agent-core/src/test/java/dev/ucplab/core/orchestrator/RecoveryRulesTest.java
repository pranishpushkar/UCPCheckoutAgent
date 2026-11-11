package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.APPROVED;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a worker does with intents a crash left behind (7.6 "What the worker picks up"). */
class RecoveryRulesTest {

    private final Lab lab = new Lab();

    private List<MerchantAttempt> completes(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    // Covers: 7.6, I3, I10 (a crash inside decide leaves the intent APPROVED and the approval PENDING; the worker that
    // continues moves the approval to APPROVED, recorded as the owner's decision, before complete leaves)
    @Test
    void advance_movesPendingApprovalToApproved_beforeCompleteIsSent_afterCrashInDecide() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        Lab.Worker dying = lab.worker("worker-a", lab.merchant, crashing);
        IntentId id = lab.propose(dying, TWO_SHIRTS).intentId();
        PendingApproval pending = lab.pending(dying, id);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);

        assertThatThrownBy(() -> dying.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.intent(id).state()).isEqualTo(APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        lab.clock.advance(lab.config.lease().plusSeconds(1));
        InterceptingGateway gateway = new InterceptingGateway(lab.merchant);
        gateway.crashBeforeNext(MerchantCall.COMPLETE);
        Lab.Worker second = lab.worker("worker-b", gateway);
        assertThatThrownBy(() -> second.orchestrator().advance(id)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);

        ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(approval.decidedBy()).contains(ALICE);
        assertThat(approval.decidedAt()).isPresent();
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isZero();

        lab.clock.advance(lab.config.lease().plusSeconds(1));
        assertThat(lab.drive(lab.worker("worker-c"), id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6, 7.5, I1, I7 (a resend that reconciliation approved is continued only by the process that observed
    // ready_for_complete; a worker that takes over reconciles again and resends only after its own get)
    @Test
    void advance_reconcilesAgain_whenResendWasApprovedByAnotherProcess() {
        Lab.Worker first = lab.worker("worker-a");
        IntentId id = lab.propose(first, TWO_SHIRTS).intentId();
        lab.decide(first, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        assertThat(first.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(lab.config.resendQuietPeriod());
        assertThat(first.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        assertThat(completes(id)).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN));

        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker second = lab.worker("worker-b");

        assertThat(second.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        assertThat(completes(id)).hasSize(1);
        assertThat(lab.merchant.log()).filteredOn(c -> c.call() == MerchantCall.COMPLETE).hasSize(1);

        assertThat(lab.drive(second, id)).isEqualTo(COMPLETED);
        assertThat(completes(id)).hasSize(2);
        assertThat(completes(id)).extracting(a -> a.idempotencyKey().orElseThrow()).containsOnly(
                completes(id).get(0).idempotencyKey().orElseThrow());
        assertThat(completes(id)).extracting(a -> a.requestBody().orElseThrow()).containsOnly(
                completes(id).get(0).requestBody().orElseThrow());
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.5, 7.6 (the process that observed ready_for_complete continues straight to the resend)
    @Test
    void advance_resendsWithoutAnotherRead_whenSameProcessApprovedTheResend() {
        Lab.Worker only = lab.worker("worker-a");
        IntentId id = lab.propose(only, TWO_SHIRTS).intentId();
        lab.decide(only, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        assertThat(only.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        lab.clock.advance(lab.config.resendQuietPeriod());
        assertThat(only.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        long readsBefore = lab.merchant.log().stream().filter(c -> c.call() == MerchantCall.GET).count();

        assertThat(only.orchestrator().advance(id).after()).isEqualTo(COMPLETED);

        assertThat(lab.merchant.log().stream().filter(c -> c.call() == MerchantCall.GET).count()).isEqualTo(readsBefore);
        assertThat(completes(id)).hasSize(2);
    }
}
