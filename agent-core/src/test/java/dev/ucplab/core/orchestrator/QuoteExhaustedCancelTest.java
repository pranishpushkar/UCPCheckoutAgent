package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
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
}
