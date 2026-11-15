package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGatewayContract;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.merchant.MerchantHarness;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * {@link UcpHttpGateway} over the real transport runs the gateway contract against the real lab merchant, started in
 * this JVM on a free port (H3 P2; 14 level 4). It is the ground truth for the scripted lab shapes
 * ({@link UcpHttpGatewayLabShapesContractTest}) and for the model ({@code FaultyMerchantModelGatewayContractTest}): all
 * three run the same situations, set up through the same lab controls (A.3).
 */
class UcpHttpGatewayLabMerchantContractTest extends MerchantGatewayContract {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final IntentId SETUP = new IntentId("setup");
    private static final FrozenBody PAY_OK = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
    private static final FrozenBody PAY_DECLINE = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_decline"));
    private static final CreateCheckout TWO_SHIRTS =
            new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("alice@example.com"));
    private static final SessionId NO_SESSION = new SessionId("chk_none");

    private static MerchantHarness merchant;

    @BeforeAll
    static void startMerchant() throws Exception {
        merchant = MerchantHarness.start();
    }

    @AfterAll
    static void stopMerchant() {
        merchant.close();
    }

    private static UcpHttpGateway gateway() {
        return new UcpHttpGateway(new MerchantEntry(SHOP, merchant.baseUrl(), "Shop", MerchantStatus.ACTIVE), CONFIG,
                new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), d -> { },
                new SplittableRandom(3), new SequentialIdGenerator("c"));
    }

    private static SessionId readySession(UcpHttpGateway g) {
        CallOutcome<CheckoutView> created = g.create(TWO_SHIRTS, IdempotencyKey.derive(SETUP, MutatingOp.CREATE, 0));
        assertThat(created).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) created).value().id();
    }

    @Override
    protected Prepared gatewayFor(Situation situation) throws Unavailable {
        merchant.reset();
        UcpHttpGateway g = gateway();
        switch (situation) {
            case CREATE_READY:
                return Prepared.of(g, TWO_SHIRTS, NO_SESSION, PAY_OK);
            case FULFILLMENT_FLOW:
                throw unavailable("the lab merchant offers no fulfillment (A.4)");
            case CREATE_REQUIRES_ESCALATION:
                throw unavailable("the lab merchant never answers requires_escalation");
            case CREATE_ALL_UNAVAILABLE:
                return Prepared.of(g, new CreateCheckout(List.of(new ProposedLine("no_such_item", 1)),
                        Optional.of("alice@example.com")), NO_SESSION, PAY_OK);
            case COMPLETE_COMPLETED, GET_AFTER_COMPLETE: {
                SessionId id = readySession(g);
                if (situation == Situation.GET_AFTER_COMPLETE) {
                    g.complete(id, PAY_OK, IdempotencyKey.derive(SETUP, MutatingOp.COMPLETE, 0));
                }
                return Prepared.of(g, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_DECLINED_AS_200:
                return Prepared.of(g, TWO_SHIRTS, readySession(g), PAY_DECLINE);
            case COMPLETE_DECLINED_AS_402:
                throw unavailable("the lab merchant sends a decline as a 200 (A.2)");
            case COMPLETE_OUT_OF_STOCK: {
                SessionId id = readySession(g);
                merchant.setStock("item_123", 1);
                return Prepared.of(g, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_PRICE_CHANGED: {
                SessionId id = readySession(g);
                merchant.setPrice("item_123", 2600);
                return Prepared.of(g, TWO_SHIRTS, id, PAY_OK);
            }
            case COMPLETE_IN_PROGRESS: {
                SessionId id = readySession(g);
                merchant.setPaymentLatencyMs(1500);
                Thread.ofVirtual().start(() -> g.complete(id, PAY_OK, IdempotencyKey.derive(SETUP, MutatingOp.COMPLETE, 0)));
                awaitStatus(g, id, CheckoutStatus.COMPLETE_IN_PROGRESS);
                return Prepared.of(g, TWO_SHIRTS, id, PAY_OK);
            }
            case GET_UNKNOWN_SESSION:
                return Prepared.of(g, TWO_SHIRTS, new SessionId("chk_9999"), PAY_OK);
            case CANCEL_OPEN:
                return Prepared.of(g, TWO_SHIRTS, readySession(g), PAY_OK);
            case ANSWER_FOR_ANOTHER_SESSION:
                throw unavailable("the real merchant answers for the session it is asked about");
            case CREDENTIAL_ECHOED_IN_ANSWER, CREDENTIAL_ECHOED_IN_ERROR:
                throw unavailable("the real merchant does not echo a credential");
            default:
                throw new AssertionError(situation);
        }
    }

    private static void awaitStatus(UcpHttpGateway g, SessionId id, CheckoutStatus wanted) {
        try {
            for (int i = 0; i < 200; i++) {
                CallOutcome<CheckoutView> read = g.get(id);
                if (read instanceof CallOutcome.Ok<CheckoutView> ok && ok.value().status() == wanted) {
                    return;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("session never reached " + wanted);
    }
}
