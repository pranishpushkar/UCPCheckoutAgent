package dev.ucplab.service.config;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantInstruments;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.ucp.UcpHttpGateway;
import java.util.List;
import java.util.Set;

/**
 * What one merchant's settings mean when the stack is built (DESIGN 19, 8.5): the instrument the agent pays it with, the
 * payment handler its gateway requires in the profile, and the check that the two agree. Nothing here reads a token
 * except {@link #instrument}, which takes it from the environment by the name {@code token_ref} gives, and no message
 * carries it (I8).
 */
final class MerchantWiring {

    private MerchantWiring() {
    }

    /** The handler the merchant's profile must list: the file's, else the lab's {@code mock_pay_1}. */
    static String requiredHandler(MerchantSettings settings) {
        return settings.paymentHandler().map(MerchantSettings.PaymentHandler::id).orElse(UcpHttpGateway.PAYMENT_HANDLER);
    }

    /**
     * The merchant's instrument: the file's {@code payment_handler} shape with the token its {@code token_ref}
     * ({@code env:NAME}) names, or the default instrument when the file names no handler.
     *
     * @throws IllegalStateException if the named variable is not set
     */
    static PaymentInstrument instrument(MerchantSettings settings, PaymentInstrument defaultInstrument,
                                        Environment environment) {
        if (settings.paymentHandler().isEmpty()) {
            return defaultInstrument;
        }
        MerchantSettings.PaymentHandler h = settings.paymentHandler().get();
        String variable = h.tokenRef().substring("env:".length());
        String token = environment.get(variable).orElseThrow(() -> new IllegalStateException(
                "the token_ref of merchant " + settings.id().value() + " names " + variable + ", which is not set"));
        return new PaymentInstrument("pi_" + settings.id().value(), h.id(), h.type(), h.credentialType(), token);
    }

    /** 8.5: the gateway's required handler and the instrument's must be equal, or the stack does not build. */
    static void requireMatchingInstrument(MerchantSettings settings, PaymentInstrument instrument) {
        MerchantInstruments.requireMatchingHandler(settings.id(), requiredHandler(settings), instrument);
    }

    static final String IN_FLIGHT_MERCHANT_REMOVED =
            "an intent in flight names a merchant that MERCHANTS_FILE does not list; restore the merchant or finish its intents";

    /**
     * DESIGN 19: every merchant that has an intent in flight must be configured, or startup refuses, because those intents
     * could never reconcile. The message is fixed: it names neither the merchant nor the file.
     */
    static void requireInFlightMerchantsConfigured(Set<MerchantId> inFlight, List<MerchantSettings> configured) {
        for (MerchantId id : inFlight) {
            if (configured.stream().noneMatch(s -> s.id().equals(id))) {
                throw new IllegalStateException(IN_FLIGHT_MERCHANT_REMOVED);
            }
        }
    }
}
