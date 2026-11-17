package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 7.2 "QUOTING, retries exhausted": with a session id the same write sets cancel_pending (7.6). */
class QuoteExhaustedCancelTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    // Covers: 7.2, 7.6, I1 (a re-quote whose update is never sent ends FAILED_NOT_CHARGED with a known session: the
    // session is cancelled, nothing is completed)
    @Test
    void advance_cancelsKnownSession_whenRequoteUpdateRetriesAreExhausted() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.UPDATE, 1, new Fault.NotSent()));
        while (lab.intent(id).state() != FAILED_NOT_CHARGED) {
            w.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(lab.intent(id).cancelPending()).isTrue();

        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 7.2 update_rejected (a re-quote's update refused with an error envelope ends FAILED_NOT_CHARGED,
    // update_rejected, at once: not retried, cancel_pending set in the same write, then the session is cancelled;
    // event quote_refused with the codes only), 7.6, 8.1, I8
    @Test
    void aRefusedUpdate_endsUpdateRejected_andCancels() {
        MerchantMessage refused = new MerchantMessage("error", "invalid_line_item", Optional.empty(), "No such line",
                Optional.of("unrecoverable"));
        Lab.Worker refusing = lab.worker("worker-r", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE,
                RewritingGateway.always(new CallOutcome.Rejected<>(200, List.of(refused)))));
        IntentId id = lab.propose(refusing, TWO_SHIRTS).intentId();
        lab.decide(refusing, id, true);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        for (int i = 0; i < 10 && lab.intent(id).state() != FAILED_NOT_CHARGED; i++) {
            refusing.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).state()).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("update_rejected");
        assertThat(lab.intent(id).cancelPending()).isTrue();
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.UPDATE).hasSize(1);
        assertThat(lab.events(id)).filteredOn(e -> e.type().equals("quote_refused")).extracting(IntentEvent::payloadJson)
                .containsExactly("{\"codes\":[\"invalid_line_item\"]}");

        assertThat(lab.drive(refusing, id)).isEqualTo(FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        assertThat(lab.merchant.orders()).isEmpty();
    }
}
