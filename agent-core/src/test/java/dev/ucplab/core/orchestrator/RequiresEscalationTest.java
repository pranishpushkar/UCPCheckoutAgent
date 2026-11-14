package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * {@code requires_escalation} is parsed only (8.1, H1 C5): a merchant that answers with it is not completable by the
 * agent, a mutating call that returns it is no longer {@code Uncertain}, and no new state or behaviour exists for it
 * (M6c). Quoting ends FAILED_NOT_CHARGED with the fixed reason {@code requires_escalation}; a complete or a reconciliation
 * read that shows it keeps the intent reconciling until the deadline, which ends it ESCALATED, and nothing is resent.
 */
class RequiresEscalationTest {

    private final Lab lab = new Lab();

    /** The lab merchant, except that the chosen calls answer with the checkout's status replaced by requires_escalation. */
    private final class EscalatingGateway implements MerchantGateway {
        final Predicate<String> escalate;
        final AtomicInteger completes = new AtomicInteger();

        EscalatingGateway(Predicate<String> escalate) {
            this.escalate = escalate;
        }

        private CallOutcome<CheckoutView> maybe(String call, CallOutcome<CheckoutView> real) {
            if (escalate.test(call) && real instanceof CallOutcome.Ok<CheckoutView> ok) {
                CheckoutView v = ok.value();
                return new CallOutcome.Ok<>(ok.httpStatus(), new CheckoutView(v.id(), CheckoutStatus.REQUIRES_ESCALATION,
                        v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(), v.order(), v.buyer(),
                        v.messages()), ok.messages());
            }
            return real;
        }

        @Override
        public MerchantProfile discover(MerchantId merchant) {
            return lab.merchant.discover(merchant);
        }

        @Override
        public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
            return maybe("create", lab.merchant.create(request, key));
        }

        @Override
        public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
            return maybe("update", lab.merchant.update(id, request, key));
        }

        @Override
        public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
            completes.incrementAndGet();
            return maybe("complete", lab.merchant.complete(id, body, key));
        }

        @Override
        public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
            return lab.merchant.cancel(id, key);
        }

        @Override
        public CallOutcome<CheckoutView> get(SessionId id) {
            return maybe("get", lab.merchant.get(id));
        }
    }

    // Covers: 8.1, 7.2 (a create answered requires_escalation is not Uncertain and not a quote: the intent ends
    // FAILED_NOT_CHARGED with the fixed reason requires_escalation, one create only, nothing is approved or completed)
    @Test
    void aQuoteThatRequiresEscalation_endsNotChargedWithAFixedReason() {
        EscalatingGateway g = new EscalatingGateway("create"::equals);
        Lab.Worker w = lab.worker("worker-a", g);

        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        IntentState end = lab.drive(w, id);

        assertThat(end).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("requires_escalation");
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.COMPLETE).isEmpty();
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 7.5, I1 (a complete answered requires_escalation is not an Uncertain and not a refusal: the intent
    // reconciles, never resends, and past the deadline ends ESCALATED with no order)
    @Test
    void aCompleteThatRequiresEscalation_reconcilesAndEscalates_withoutAResend() {
        EscalatingGateway g = new EscalatingGateway(c -> c.equals("complete") || c.equals("get"));
        Lab.Worker w = lab.worker("worker-a", g);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);

        IntentState end = lab.drive(w, id, 400);

        assertThat(end).isEqualTo(IntentState.ESCALATED);
        assertThat(g.completes.get()).as("complete calls").isEqualTo(1);
        assertThat(lab.merchant.orders()).hasSizeLessThanOrEqualTo(1);
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.COMPLETE).hasSize(1);
        List<IntentEvent> events = lab.events(id);
        assertThat(events).anySatisfy(e -> assertThat(e.toString()).contains("requires_escalation"));
    }

    // Covers: 8.1, 7.5 (a reconciliation read that shows requires_escalation: the intent keeps waiting, then ESCALATED
    // at the deadline; no resend, not completed)
    @Test
    void aReadThatShowsRequiresEscalation_keepsWaiting_thenEscalates() {
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 1,
                new dev.ucplab.core.merchant.fake.Fault.CommitThenDrop()));
        EscalatingGateway g = new EscalatingGateway("get"::equals);
        Lab.Worker w = lab.worker("worker-a", g);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);

        IntentState end = lab.drive(w, id, 400);

        assertThat(end).isEqualTo(IntentState.ESCALATED);
        assertThat(g.completes.get()).as("complete calls").isEqualTo(1);
    }
}
