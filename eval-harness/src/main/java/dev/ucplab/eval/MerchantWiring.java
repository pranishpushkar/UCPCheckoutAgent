package dev.ucplab.eval;

import dev.ucplab.core.merchant.FulfillmentPolicy;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.ucp.UcpHttpGateway;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What differs between merchants in a stack (8.5, 19, H1): the payment handler the gateway requires in the profile, the
 * UCP versions the operator pins, the shipping policy, and the instrument the agent pays with, built from the token. The
 * stack checks that the handler and the instrument's handler are equal and refuses to build otherwise.
 */
record MerchantWiring(
        String handlerId, Set<String> ucpVersions, FulfillmentPolicy fulfillmentPolicy,
        Function<String, PaymentInstrument> instrument) {

    /** The lab merchant: handler {@code mock_pay_1}, version 2026-08-25, the lab's mock card. */
    static final MerchantWiring LAB = new MerchantWiring(UcpHttpGateway.PAYMENT_HANDLER,
            Set.of(UcpHttpGateway.SUPPORTED_VERSION), FulfillmentPolicy.cheapest(),
            token -> PaymentInstrument.mockCard("pi_eval", token));

    MerchantWiring {
        Objects.requireNonNull(handlerId, "handlerId");
        ucpVersions = Set.copyOf(Objects.requireNonNull(ucpVersions, "ucpVersions"));
        Objects.requireNonNull(fulfillmentPolicy, "fulfillmentPolicy");
        Objects.requireNonNull(instrument, "instrument");
    }
}
