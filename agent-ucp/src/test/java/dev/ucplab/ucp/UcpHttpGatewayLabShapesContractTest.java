package dev.ucplab.ucp;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGatewayContract;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.ucp.ScriptedTransport.Route;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * {@link UcpHttpGateway} runs the gateway contract over answers in the lab merchant's wire shapes (H3 P2; 8.1, Appendix
 * B, I4, I8). The answers are the real merchant's, recorded in {@code src/test/resources/merchant}; the few the
 * recordings lack are those answers with only the named field changed, as {@code StubFaultContractTest} already does.
 */
class UcpHttpGatewayLabShapesContractTest extends MerchantGatewayContract {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final MerchantEntry ENTRY =
            new MerchantEntry(SHOP, URI.create("http://127.0.0.1:8081"), "Shop", MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final String CANARY = "tok_canary_9f3a";
    private static final CreateCheckout TWO_SHIRTS =
            new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("a@example.com"));

    private static String fixture(String name) {
        try (InputStream in = UcpHttpGatewayLabShapesContractTest.class.getResourceAsStream("/merchant/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpReply reply(int status, String name) {
        return ScriptedTransport.reply(status, fixture(name));
    }

    private static FrozenBody pay(String token) {
        return CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", token));
    }

    private static Prepared prepared(ScriptedTransport transport, String session, String token) {
        TestClock clock = new TestClock();
        UcpHttpGateway gateway = new UcpHttpGateway(ENTRY, CONFIG, transport, new CircuitBreakers(CONFIG, clock), clock,
                d -> { }, new SplittableRandom(3), new SequentialIdGenerator("r"));
        return Prepared.of(gateway, TWO_SHIRTS, new SessionId(session), pay(token));
    }

    private static ScriptedTransport merchant() {
        return new ScriptedTransport().on(Route.DISCOVERY, reply(200, "discovery"));
    }

    @Override
    protected Prepared gatewayFor(Situation situation) throws Unavailable {
        ScriptedTransport t = merchant();
        switch (situation) {
            case CREATE_READY:
                return prepared(t.on(Route.CREATE, reply(201, "create_ready")), "chk_1001", "tok_ok");
            case FULFILLMENT_FLOW:
                throw unavailable("the lab merchant offers no fulfillment (A.4)");
            case CREATE_REQUIRES_ESCALATION:
                return prepared(t.on(Route.CREATE, ScriptedTransport.reply(201,
                        fixture("create_ready").replace("ready_for_complete", "requires_escalation"))), "chk_1001", "tok_ok");
            case CREATE_ALL_UNAVAILABLE:
                return prepared(t.on(Route.CREATE, reply(200, "create_all_unavailable")), "chk_1001", "tok_ok");
            case COMPLETE_COMPLETED:
                return prepared(t.on(Route.COMPLETE, reply(200, "complete_completed")), "chk_1006", "tok_ok");
            case COMPLETE_DECLINED_AS_200:
                return prepared(t.on(Route.COMPLETE, reply(200, "complete_declined")), "chk_1004", "tok_decline");
            case COMPLETE_DECLINED_AS_402:
                throw unavailable("the lab merchant sends a decline as a 200 (A.2); only the sample sends a 402");
            case COMPLETE_OUT_OF_STOCK:
                return prepared(t.on(Route.COMPLETE, reply(200, "complete_out_of_stock")), "chk_1005", "tok_ok");
            case COMPLETE_PRICE_CHANGED:
                return prepared(t.on(Route.COMPLETE, reply(200, "complete_price_changed")), "chk_1001", "tok_ok");
            case COMPLETE_IN_PROGRESS: {
                // The merchant's answer to a second complete (MerchantCore.complete): the session as get_in_progress shows
                // it, plus the one recoverable error "complete_in_progress".
                String busy = fixture("get_in_progress").replace("\"status\":\"complete_in_progress\"",
                        "\"messages\":[{\"code\":\"complete_in_progress\",\"content\":\"Checkout is already being completed\","
                                + "\"severity\":\"recoverable\",\"type\":\"error\"}],\"status\":\"complete_in_progress\"");
                return prepared(t.on(Route.COMPLETE, ScriptedTransport.reply(200, busy))
                        .on(Route.GET, reply(200, "get_in_progress")), "chk_1007", "tok_ok");
            }
            case GET_UNKNOWN_SESSION:
                return prepared(t.on(Route.GET, reply(404, "error_not_found")), "chk_9999", "tok_ok");
            case GET_AFTER_COMPLETE:
                return prepared(t.on(Route.GET, reply(200, "complete_completed")), "chk_1006", "tok_ok");
            case CANCEL_OPEN:
                return prepared(t.on(Route.CANCEL, reply(200, "cancel_canceled")), "chk_1008", "tok_ok");
            case ANSWER_FOR_ANOTHER_SESSION:
                // The session asked for is chk_1002; the merchant answers with chk_1001 whatever it is asked.
                return prepared(t.onEveryCall(reply(200, "get_ready")), "chk_1002", "tok_ok");
            case CREDENTIAL_ECHOED_IN_ANSWER: {
                // The merchant's decline, with the credential echoed into the content and the path of its error.
                String echoed = fixture("complete_declined")
                        .replace("card_declined", CANARY).replace("\"$.payment\"", "\"$.payment.credential." + CANARY + "\"");
                return prepared(t.on(Route.COMPLETE, ScriptedTransport.reply(200, echoed)), "chk_1004", CANARY).withCanary(CANARY);
            }
            case CREDENTIAL_ECHOED_IN_ERROR: {
                String refusal = "{\"code\":\"payment_invalid\",\"content\":\"Rejected credential " + CANARY + "\"}";
                return prepared(t.on(Route.COMPLETE, ScriptedTransport.reply(400, refusal)), "chk_1004", CANARY).withCanary(CANARY);
            }
            default:
                throw new AssertionError(situation);
        }
    }
}
