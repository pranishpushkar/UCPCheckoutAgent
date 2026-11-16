package dev.ucplab.ucp;

import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantGatewayContract;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.ucp.auth.ClientCredentials;
import java.util.Optional;
import java.util.Set;

/**
 * {@link McpMerchantGateway} runs the gateway contract over the store's recorded answers (H5 C6; H3 port parity; 8.1,
 * 8.4, Appendix B). A situation the recordings do not hold is skipped, never invented; the reasons are the port-parity
 * list's entry for this gateway. Two kinds of clause cannot apply at all: every complete situation, because 8.4 offers
 * no complete over MCP and N1 rules one out ({@code complete} is the second guard, NotSent,
 * {@link McpMerchantGatewayTest#complete_sendsNothing}); and the I8 clauses, which plant the payment token of a
 * complete body (the MCP credentials and the buyer IP have their own canary,
 * {@link McpMerchantGatewayTest#i8_theSecretAloneTheTokenAndTheBuyerIp_neverComeBack}).
 */
class McpMerchantGatewayContractTest extends MerchantGatewayContract {

    private static final String NO_COMPLETE = "8.4 offers no complete over MCP and N1 rules one out: complete is never"
            + " sent (McpMerchantGatewayTest.complete_sendsNothing)";

    @Override
    protected Prepared gatewayFor(Situation situation) throws Unavailable {
        McpMerchantGatewayTest setup = new McpMerchantGatewayTest();
        McpMerchantGateway gateway = setup.gateway(McpMerchantGatewayTest.entry(Completion.HANDOFF), Optional.empty(),
                new ClientCredentials("client-1", "secret-1"), Set.of());
        Prepared prepared = Prepared.of(gateway, McpMerchantGatewayTest.ONE_BOARD, McpMerchantGatewayTest.RECORDED,
                CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok")));
        switch (situation) {
            case CANCEL_OPEN:
                setup.store.answer("cancel_checkout", "cancel_checkout.json");
                return prepared;
            case CREATE_READY:
                throw unavailable("the recorded create has no buyer and is incomplete (create_checkout.json); no"
                        + " recording of a ready create");
            case FULFILLMENT_FLOW:
                throw unavailable("no recording of a shipping selection over MCP");
            case CREATE_REQUIRES_ESCALATION:
                throw unavailable("no recording of the store answering requires_escalation (the status is parsed:"
                        + " McpMerchantGatewayTest.create_answeredRequiresEscalation_isOkWithThatStatus)");
            case CREATE_ALL_UNAVAILABLE:
                throw unavailable("no recording of a create with every item unavailable (VERIFY 8)");
            case GET_UNKNOWN_SESSION:
            case GET_AFTER_COMPLETE:
                throw unavailable("no recording of get_checkout (VERIFY 9)");
            case ANSWER_FOR_ANOTHER_SESSION:
                throw unavailable("the clause asserts a complete; update, cancel and get are covered by"
                        + " McpMerchantGatewayTest.anAnswerAboutAnotherSession_isRefused. " + NO_COMPLETE);
            case COMPLETE_COMPLETED:
            case COMPLETE_DECLINED_AS_200:
            case COMPLETE_DECLINED_AS_402:
            case COMPLETE_OUT_OF_STOCK:
            case COMPLETE_PRICE_CHANGED:
            case COMPLETE_IN_PROGRESS:
            case CREDENTIAL_ECHOED_IN_ANSWER:
            case CREDENTIAL_ECHOED_IN_ERROR:
                throw unavailable(NO_COMPLETE);
            default:
                throw new AssertionError(situation);
        }
    }
}
