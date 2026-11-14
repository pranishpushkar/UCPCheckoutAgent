package dev.ucplab.core.merchant.fake;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGatewayContract;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link FaultyMerchantModel} runs the gateway contract (H3 P2; 8.1, Appendix B, I4, I8; 14 level 2: the model must match
 * the reference merchant, and the property tests are only as good as that match). Every situation is set up through the
 * model's lab controls, exactly as the real merchant's are (A.3).
 */
class FaultyMerchantModelGatewayContractTest extends MerchantGatewayContract {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    /** The setup's own intent: its keys never collide with the contract's. */
    private static final IntentId SETUP = new IntentId("setup");
    private static final FrozenBody PAY_OK = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
    private static final FrozenBody PAY_DECLINE = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_decline"));
    private static final CreateCheckout TWO_SHIRTS =
            new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("alice@example.com"));
    private static final SessionId NO_SESSION = new SessionId("cs_none");

    private static FaultyMerchantModel merchant() {
        return new FaultyMerchantModel(SHOP, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
                new SequentialIdGenerator("m"));
    }

    private static SessionId readySession(FaultyMerchantModel m) {
        CallOutcome<CheckoutView> created = m.create(TWO_SHIRTS, IdempotencyKey.derive(SETUP, MutatingOp.CREATE, 0));
        assertThat(created).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) created).value().id();
    }

    @Override
    protected Prepared gatewayFor(Situation situation) throws Unavailable {
        FaultyMerchantModel m = merchant();
        switch (situation) {
            case CREATE_READY:
                return Prepared.of(m, TWO_SHIRTS, NO_SESSION, PAY_OK);
            case FULFILLMENT_FLOW:
                m.enableFulfillment();
                return Prepared.of(m, TWO_SHIRTS, NO_SESSION, PAY_OK);
            case CREATE_REQUIRES_ESCALATION:
                throw unavailable("the model has no control that answers a create with requires_escalation");
            case CREATE_ALL_UNAVAILABLE:
                return Prepared.of(m, new CreateCheckout(List.of(new ProposedLine("no_such_item", 1)),
                        Optional.of("alice@example.com")), NO_SESSION, PAY_OK);
            case COMPLETE_COMPLETED, GET_AFTER_COMPLETE: {
                SessionId id = readySession(m);
                if (situation == Situation.GET_AFTER_COMPLETE) {
                    m.complete(id, PAY_OK, IdempotencyKey.derive(SETUP, MutatingOp.COMPLETE, 0));
                }
                return Prepared.of(m, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_DECLINED_AS_200, COMPLETE_DECLINED_AS_402:
                // The model has no wire status: its one answer to a decline is the lab's 200 form, which is also what the
                // HTTP gateway reports for a 402 (8.1). Both situations therefore set up the same decline.
                return Prepared.of(m, TWO_SHIRTS, readySession(m), PAY_DECLINE);
            case COMPLETE_OUT_OF_STOCK: {
                SessionId id = readySession(m);
                m.setStock("item_123", 1);
                return Prepared.of(m, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_PRICE_CHANGED: {
                SessionId id = readySession(m);
                m.setPrice("item_123", 2600);
                return Prepared.of(m, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_IN_PROGRESS: {
                SessionId id = readySession(m);
                m.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.HoldInProgress(Duration.ofSeconds(3))));
                m.complete(id, PAY_OK, IdempotencyKey.derive(SETUP, MutatingOp.COMPLETE, 0));
                return Prepared.of(m, TWO_SHIRTS, id, PAY_OK);
            }
            case GET_UNKNOWN_SESSION:
                return Prepared.of(m, TWO_SHIRTS, new SessionId("chk_9999"), PAY_OK);
            case CANCEL_OPEN:
                return Prepared.of(m, TWO_SHIRTS, readySession(m), PAY_OK);
            case ANSWER_FOR_ANOTHER_SESSION:
                throw unavailable("the model answers from its own sessions; it has no transport that could misdirect an answer");
            case CREDENTIAL_ECHOED_IN_ANSWER, CREDENTIAL_ECHOED_IN_ERROR:
                throw unavailable("the model never echoes a credential: it has no control that does, and no scrubbing (H3 P2 gap)");
            default:
                throw new AssertionError(situation);
        }
    }
}
