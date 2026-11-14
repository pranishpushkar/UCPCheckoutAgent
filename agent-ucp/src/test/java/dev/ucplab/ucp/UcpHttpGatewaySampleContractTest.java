package dev.ucplab.ucp;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGatewayContract;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.ucp.ScriptedTransport.Route;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * {@link UcpHttpGateway} runs the gateway contract over the official sample merchant's recorded answers (H3 P2; 8.1,
 * Appendix B, I4, I8; docs/interop/fixtures, the same {@link InteropFixtures} as {@code SampleMerchantContractTest}). A
 * situation the sample's recordings do not contain is skipped, never invented; the two credential situations change only
 * the text of a recorded refusal.
 */
class UcpHttpGatewaySampleContractTest extends MerchantGatewayContract {

    private static final MerchantId SAMPLE = new MerchantId("sample");
    private static final MerchantEntry ENTRY = new MerchantEntry(SAMPLE,
            URI.create("http://127.0.0.1:" + InteropFixtures.PORT), "Sample", MerchantStatus.ACTIVE);
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static final String CANARY = "tok_canary_9f3a";
    private static final CreateCheckout ROSES =
            new CreateCheckout(List.of(new ProposedLine("bouquet_roses", 1)), Optional.of("john.doe@example.com"));

    private static HttpReply reply(String fixture) {
        return InteropFixtures.reply(fixture);
    }

    private static Prepared prepared(ScriptedTransport transport, String session, String token) {
        TestClock clock = new TestClock();
        UcpHttpGateway gateway = new UcpHttpGateway(ENTRY, CONFIG, transport, new CircuitBreakers(CONFIG, clock), clock,
                d -> { }, new SplittableRandom(3), new SequentialIdGenerator("r"), Set.of(), "mock_payment_handler",
                Set.of("2026-04-08"));
        return Prepared.of(gateway, ROSES, new SessionId(session),
                CheckoutWire.completeBody(PaymentInstrument.sampleCard("instr_1", token)));
    }

    private static ScriptedTransport merchant() {
        return new ScriptedTransport().on(Route.DISCOVERY, reply("01-discovery"));
    }

    @Override
    protected Prepared gatewayFor(Situation situation) throws Unavailable {
        ScriptedTransport t = merchant();
        switch (situation) {
            case CREATE_READY:
                throw unavailable("the sample needs a shipping selection before a checkout is ready: no recording of a "
                        + "ready create (fixtures 04, 07)");
            case FULFILLMENT_FLOW:
                // Fixtures 04 to 07: the create, then the method, the destination and the option.
                return prepared(t.on(Route.CREATE, reply("04-happy-create")).on(Route.UPDATE,
                        reply("05-happy-fulfillment-1-methods"), reply("06-happy-fulfillment-2-destination"),
                        reply("07-happy-fulfillment-3-option")), "<id-13>", "tok");
            case CREATE_REQUIRES_ESCALATION:
                throw unavailable("no recording of the sample answering requires_escalation");
            case CREATE_ALL_UNAVAILABLE:
                throw unavailable("no recording of a create with every item unavailable");
            case COMPLETE_COMPLETED:
                return prepared(t.on(Route.COMPLETE, reply("08-happy-complete")), "<id-13>", "tok");
            case COMPLETE_DECLINED_AS_200:
                throw unavailable("the sample sends a decline as a 402 (fixture 31), never as a 200");
            case COMPLETE_DECLINED_AS_402:
                return prepared(t.on(Route.COMPLETE, reply("31-decline-complete-fail-token")), "<id-72>", "tok_fail");
            case COMPLETE_OUT_OF_STOCK:
                return prepared(t.on(Route.COMPLETE, reply("26-stock-complete-sold-out")), "<id-59>", "tok");
            case COMPLETE_PRICE_CHANGED:
                throw unavailable("the sample re-prices without a price_changed answer: fixtures 16 and 21 complete at the new price");
            case COMPLETE_IN_PROGRESS:
                throw unavailable("no recording of a complete_in_progress session");
            case GET_UNKNOWN_SESSION:
                return prepared(t.on(Route.GET, reply("33-get-unknown-id")), "does-not-exist", "tok");
            case GET_AFTER_COMPLETE:
                return prepared(t.on(Route.GET, reply("09-get-after-complete")), "<id-13>", "tok");
            case CANCEL_OPEN:
                throw unavailable("no recording of a cancel");
            case ANSWER_FOR_ANOTHER_SESSION:
                // The session asked for is <id-1>; the sample answers with <id-13> whatever it is asked.
                return prepared(t.onEveryCall(reply("09-get-after-complete")), "<id-1>", "tok");
            case CREDENTIAL_ECHOED_IN_ANSWER: {
                // Fixture 31's 402 decline, with the credential echoed into its content.
                String echoed = ScriptedTransport.text(reply("31-decline-complete-fail-token"))
                        .replace("Insufficient Funds (Mock)", "Insufficient Funds " + CANARY);
                return prepared(t.on(Route.COMPLETE, ScriptedTransport.reply(402, echoed)), "<id-72>", CANARY).withCanary(CANARY);
            }
            case CREDENTIAL_ECHOED_IN_ERROR: {
                // Fixture 26's 409 refusal envelope with a code we do not know, and the credential in its content.
                String refusal = ScriptedTransport.text(reply("26-stock-complete-sold-out"))
                        .replace("OUT_OF_STOCK", "INVALID_CREDENTIAL").replace("Item bouquet_roses is out of stock",
                                "Rejected credential " + CANARY);
                return prepared(t.on(Route.COMPLETE, ScriptedTransport.reply(422, refusal)), "<id-59>", CANARY).withCanary(CANARY);
            }
            default:
                throw new AssertionError(situation);
        }
    }
}
