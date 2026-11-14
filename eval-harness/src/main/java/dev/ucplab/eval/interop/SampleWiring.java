package dev.ucplab.eval.interop;

import dev.ucplab.core.merchant.FulfillmentPolicy;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.eval.MerchantWiring;
import java.util.Set;

/** The harness's stack wiring for the official sample merchant (H1 plan sections 3 and 7). */
public final class SampleWiring {

    /** The sample's documented test tokens (its README and sample client): a charge succeeds, and one is declined. */
    public static final String SUCCESS_TOKEN = "success_token";
    public static final String FAIL_TOKEN = "fail_token";
    public static final String HANDLER = "mock_payment_handler";
    public static final String VERSION = "2026-04-08";

    private SampleWiring() {
    }

    /** Handler {@code mock_payment_handler}, pinned to 2026-04-08, {@code FulfillmentPolicy.cheapest()}, the card-token shape. */
    public static MerchantWiring of(String ucpAgent) {
        return new MerchantWiring(HANDLER, Set.of(VERSION), FulfillmentPolicy.cheapest(),
                token -> PaymentInstrument.sampleCard("pi_eval", token), ucpAgent);
    }
}
