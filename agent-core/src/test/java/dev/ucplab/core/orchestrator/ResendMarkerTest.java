package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.RECONCILING;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The resend marker a process keeps after reconciliation approved a resend (7.5, 7.6) is the version the approving
 * write stored, on both branches of that write.
 */
class ResendMarkerTest {

    private final Lab lab = new Lab();
    private final SkewingIntentRepository store = new SkewingIntentRepository(lab.intents);
    private final Lab.Worker worker = lab.worker("worker-a", lab.merchant, lab.approvals, store);

    private List<MerchantAttempt> completes(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).toList();
    }

    /** APPROVED, then a complete that never reaches the merchant: RECONCILING, newest complete attempt UNCERTAIN. */
    private IntentId reconcilingAfterLostComplete() {
        IntentId id = lab.propose(worker, TWO_SHIRTS).intentId();
        lab.decide(worker, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(RECONCILING);
        return id;
    }

    // Covers: 7.5, 7.6, I7 (approving write is an update: the newest complete attempt is already UNCERTAIN; the stored
    // version is not the caller's expected + 1, and the same process still continues straight to the resend)
    @Test
    void advance_resends_whenApprovingWriteIsAnUpdateThatStoredAnotherVersion() {
        IntentId id = reconcilingAfterLostComplete();
        lab.clock.advance(lab.config.resendQuietPeriod());
        store.skewNextWrite();

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        long reads = lab.merchant.log().stream().filter(c -> c.call() == MerchantCall.GET).count();

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETED);
        assertThat(lab.merchant.log().stream().filter(c -> c.call() == MerchantCall.GET).count()).isEqualTo(reads);
        assertThat(completes(id)).hasSize(2);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.5, 7.6, I7 (approving write resolves a complete attempt left SENT together with the move; same check)
    @Test
    void advance_resends_whenApprovingWriteResolvesASentAttemptAndStoredAnotherVersion() {
        IntentId id = reconcilingAfterLostComplete();
        PurchaseIntent reconciling = lab.intent(id);
        MerchantAttempt first = completes(id).get(0);
        MerchantAttempt leftSent = MerchantAttempt.sent(new AttemptId("left-sent"), id, AttemptOp.COMPLETE,
                first.revision(), first.idempotencyKey(), first.requestBody(), lab.clock.instant());
        lab.intents.recordAttempt(leftSent, reconciling, reconciling.version(), "worker-a");
        lab.clock.advance(lab.config.resendQuietPeriod());
        store.skewNextWrite();

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETING);
        assertThat(completes(id)).last().satisfies(a -> assertThat(a.status()).isEqualTo(AttemptStatus.UNCERTAIN));

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(COMPLETED);
        assertThat(completes(id)).hasSize(3);
        assertThat(lab.merchant.orders()).hasSize(1);
    }
}
