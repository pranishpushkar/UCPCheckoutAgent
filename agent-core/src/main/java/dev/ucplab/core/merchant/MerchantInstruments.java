package dev.ucplab.core.merchant;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Helpers for the instrument-by-merchant function the orchestrator takes (19: the handler is the merchant's). */
public final class MerchantInstruments {

    private MerchantInstruments() {
    }

    /**
     * The wiring rule of 8.5: the payment handler id a merchant's gateway requires in its profile and the {@code handlerId}
     * of the instrument the agent pays that merchant with must be equal, or the stack does not build. The message names the
     * merchant and both handler ids and nothing else: never the token.
     *
     * @throws IllegalStateException if they differ
     */
    public static void requireMatchingHandler(MerchantId merchant, String gatewayHandlerId, PaymentInstrument instrument) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(gatewayHandlerId, "gatewayHandlerId");
        Objects.requireNonNull(instrument, "instrument");
        if (!gatewayHandlerId.equals(instrument.handlerId())) {
            throw new IllegalStateException("payment handler mismatch for merchant " + merchant + ": the gateway requires "
                    + gatewayHandlerId + " but the instrument is for " + instrument.handlerId());
        }
    }

    /** The instrument of each merchant in {@code byMerchant}; any other merchant throws {@link NoInstrumentException}. */
    public static Function<MerchantId, PaymentInstrument> of(Map<MerchantId, PaymentInstrument> byMerchant) {
        Map<MerchantId, PaymentInstrument> copy = Map.copyOf(Objects.requireNonNull(byMerchant, "byMerchant"));
        return id -> {
            PaymentInstrument instrument = copy.get(Objects.requireNonNull(id, "id"));
            if (instrument == null) {
                throw new NoInstrumentException(id);
            }
            return instrument;
        };
    }
}
